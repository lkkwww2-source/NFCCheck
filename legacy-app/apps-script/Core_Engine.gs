// [Core_Engine.gs] 출결 연산 마스터 엔진
// ─────────────────────────────────────────────
// NFC학생현황 시트가 유일한 학생 명부다. 참여명단입력(N월) 사전신청 명단은 더 이상 쓰지 않으며,
// 월 구분 없이 NFC학생현황에 등록된 학생이면 날짜/타임에 상관없이 자유롭게 출결을 기록한다.
//
// 결석은 더 이상 "명단에는 있는데 안 왔다"를 자동으로 추론하지 않는다. 결석으로 남는 경우는
// 다음 세 가지뿐이다.
//   1) 학생이 index.html에서 "사유 제출"로 미리 결석을 알린 경우 (웹앱사유)
//   2) 담임이 Teacher.html에서 직접 "결석 처리"한 경우 (교사결석) - markAbsentByTeacher()
//   3) 담임이 안드로이드 앱에서 수동으로 "결석"을 눌러 보낸 경우 (수동결석)
// 자동 판정은 없다.
// ─────────────────────────────────────────────

// 시트를 사람이 직접 고쳤거나 낱개 저장 중 집계가 한 번이라도 실패했을 때를 대비한 주기적
// 정합성 재계산. 개별 저장 경로(submitWebAttendance 등)는 더 이상 이 트리거에 의존하지 않는다.
function runScheduledAttendance() {
  syncAttendance();
}

// 거의 동시에 들어온 저장 요청 두 개가 "아직 이 줄이 없네" → 둘 다 새 줄을 만드는 식으로
// 중복 기록을 만들지 못하도록, 출결을 저장하는 요청은 한 번에 하나씩만 실행되게 묶는다.
// (createResultUpserter_가 행 위치를 한 번만 읽어서 재사용하는 방식이라, 저장 요청끼리 겹치면
// 서로 다른 줄로 착각해 중복 행이 생길 수 있다. 저장 자체가 가벼워졌기 때문에 순서대로 처리해도
// 눈에 띄게 느려지지 않는다.)
function withAttendanceLock_(fn) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(20000);
  } catch (e) {
    throw new Error('다른 출결 저장이 진행 중입니다. 잠시 후 다시 시도해 주세요.');
  }
  try {
    return fn();
  } finally {
    lock.releaseLock();
  }
}

// NFC학생현황 시트의 D열은 '반'이 아니라 '등록여부'라서(현재 쓰지 않음), 반은 학번 자릿수로
// 계산한다: 4자리 학번은 2번째 자리, 5자리 이상은 2~3번째 자리가 반.
function classNameFromStudentId_(studentId) {
  var id = String(studentId || '').trim();
  if (id.length === 4) return id.substring(1, 2) + '반';
  if (id.length >= 5) return id.substring(1, 3).replace(/^0/, '') + '반';
  return '';
}

function buildStudentMasterMap_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName('NFC학생현황');
  var map = {};
  if (!sheet || sheet.getLastRow() < 2) return map;

  var data = sheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var studentId = cleanStudentId(row[0]);
    var name = String(row[1] || '').trim();
    var nfcId = String(row[2] || '').trim();
    if (!studentId) continue;
    map[studentId] = { name: name, nfcId: nfcId, className: classNameFromStudentId_(studentId), affiliation: '' };
  }
  return map;
}

function getAttendanceSheet_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName('웹앱응답');
  var header = ['타임스탬프', '학번', '신청월', '요일', '타임', '실제날짜', '입력방식', '사전내용'];
  if (!sheet) {
    sheet = ss.insertSheet('웹앱응답');
    sheet.getRange(1, 1, 1, header.length).setValues([header]);
  }
  return sheet;
}

function buildTimeMap_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var timeSheet = ss.getSheetByName('QR기준시간');
  var timeMap = {};
  if (!timeSheet) return timeMap;
  var timeData = timeSheet.getDataRange().getValues();
  for (var i = 1; i < timeData.length; i++) {
    var day = String(timeData[i][0] || '').trim();
    var period = String(timeData[i][1] || '').trim();
    var startTime = timeData[i][2];
    var lateTime = timeData[i][3];
    if (!day || !period || !startTime) continue;
    timeMap[day + '|' + period] = { start: startTime, late: lateTime || startTime };
  }
  return timeMap;
}

// 입력방식 문자열 → 출결 상태. '웹앱사유'/'교사결석'은 상태 단어를 포함하지 않으므로 먼저 매칭한다.
function decodeAttendanceStatus_(inputMethod) {
  var text = String(inputMethod || '').trim();
  if (text === '웹앱사유') return '결석';
  if (text === '교사결석') return '결석';
  if (text.indexOf('결석') !== -1) return '결석';
  if (text.indexOf('지각') !== -1) return '지각';
  if (text.indexOf('학사') !== -1) return '학사';
  if (text.indexOf('출석') !== -1) return '출석';
  return '';
}

function encodeAttendanceMethod_(inputType, status) {
  return String(inputType || '') + String(status || '');
}

// 폼응답(레거시 구글폼 QR) + 웹앱응답(QR 자가출석/안드로이드 NFC·수동/교사결석/학생 사전제출)을
// 하나의 "실제로 벌어진 출결 이벤트" 목록으로 합친다.
//
// ⚠️ 각 행에 이미 저장된 신청월/요일/타임/실제날짜를 그대로 신뢰한다 — 지금 시각으로 다시
// 계산하지 않는다. (과거 버그: getWebActualTimeInfo(row[0])로 재계산하면서, 나중에 지난 날짜를
// 정정 입력해도 당일/다른 타임으로 잘못 집계되어 통계에서 빠지던 문제를 여기서 고친다.)
function buildUnifiedResponseData_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var rows = [];

  var formSheet = ss.getSheetByName('폼응답');
  if (formSheet && formSheet.getLastRow() >= 2) {
    var formData = formSheet.getDataRange().getValues();
    for (var i = 1; i < formData.length; i++) {
      var row = formData[i];
      var studentId = cleanStudentId(row[1]);
      var dateRaw = row[5];
      if (!studentId || !dateRaw) continue;
      rows.push({
        timestamp: row[0], studentId: studentId,
        dateKey: formatDateKey(dateRaw), day: String(row[3] || '').trim(),
        period: String(row[4] || '').trim(), status: '출석', note: ''
      });
    }
  }

  var webSheet = ss.getSheetByName('웹앱응답');
  if (webSheet && webSheet.getLastRow() >= 2) {
    var webData = webSheet.getDataRange().getValues();
    for (var i = 1; i < webData.length; i++) {
      var row = webData[i];
      var studentId = cleanStudentId(row[1]);
      var dateRaw = row[5];
      if (!studentId || !dateRaw) continue;
      var status = decodeAttendanceStatus_(row[6]);
      if (!status) continue;
      rows.push({
        timestamp: row[0], studentId: studentId,
        dateKey: normalizeDateText(dateRaw), day: String(row[3] || '').trim(),
        period: String(row[4] || '').trim(), status: status, note: String(row[7] || '').trim()
      });
    }
  }

  return rows;
}

// 출결결과 재계산. 참여명단입력(N월) 사전신청 명단을 더 이상 참조하지 않는다. 같은
// (날짜,타임,학번)에 기록이 여러 번 남아도 가장 마지막(최신 타임스탬프) 기록이 최종 상태가
// 된다 — 같은 날짜/타임을 다시 보내면 정정으로 처리된다.
function syncAttendance() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var resultSheet = ss.getSheetByName('출결결과');
  if (!resultSheet) { throw new Error('출결결과 시트 없음'); }

  var masterMap = buildStudentMasterMap_();
  var unified = buildUnifiedResponseData_();

  var byKey = {};
  unified.forEach(function (r) {
    if (!r.dateKey || !r.period || !r.studentId) return;
    var key = r.dateKey + '|' + r.period + '|' + r.studentId;
    var prev = byKey[key];
    if (!prev || new Date(r.timestamp).getTime() >= new Date(prev.timestamp).getTime()) {
      byKey[key] = r;
    }
  });

  var timeMap = buildTimeMap_();
  var resultHeader = ['실제날짜', '신청월', '요일', '타임', '학번', '이름', '반', '소속', '참여구분', '제출시간', '출결', '입실상태', '차이시간(분)', '비고'];

  var rows = Object.keys(byKey).map(function (key) {
    var r = byKey[key];
    var master = masterMap[r.studentId] || {};
    var month = r.dateKey.substring(0, 7);
    var day = r.day || dayOfWeekKo_(parseDateKey(r.dateKey));
    var qrLike = (r.status === '출석' || r.status === '지각') ? { timestamp: r.timestamp } : null;
    var timingInfo = getTimingInfo(qrLike, day, r.period, timeMap);
    var submitTimeText = r.timestamp ? formatDateTimeKo(r.timestamp) : '';
    return [
      parseDateKey(r.dateKey), month, day, r.period, r.studentId,
      master.name || '', master.className || '', master.affiliation || '',
      '자율학습', submitTimeText, r.status, timingInfo.status, timingInfo.minutes, r.note
    ];
  });

  rows.sort(function (a, b) {
    var da = new Date(a[0]).getTime(), db = new Date(b[0]).getTime();
    if (da !== db) return da - db;
    if (String(a[6]) !== String(b[6])) return String(a[6]).localeCompare(String(b[6]), 'ko');
    if (String(a[3]) !== String(b[3])) return String(a[3]).localeCompare(String(b[3]), 'ko');
    return String(a[4]).localeCompare(String(b[4]), 'ko');
  });

  resultSheet.clearContents();
  resultSheet.getRange(1, 1, 1, resultHeader.length).setValues([resultHeader]);
  if (rows.length > 0) {
    resultSheet.getRange(2, 1, rows.length, resultHeader.length).setValues(rows);
    resultSheet.getRange(2, 1, rows.length, 1).setNumberFormat('yyyy. m. d');
    resultSheet.getRange(2, 2, rows.length, 1).setNumberFormat('@STRING@');
    resultSheet.getRange(2, 5, rows.length, 1).setNumberFormat('@STRING@');
  }

  updateTeacherApprovalCheck();
}

// ─────────────────────────────────────────────
// 기록 한 건을 저장할 때마다 출결결과/담임허락체크 "시트 전체"를 지우고 다시 쓰면(옛 syncAttendance
// 방식), 학기가 지나 기록이 쌓일수록 그 한 번의 저장이 점점 느려져서 휴대폰에서 타임아웃이
// 자주 뜨게 된다. 그래서 실제 저장 경로(submitWebAttendance, saveAndroidAttendance,
// markAbsentByTeacher)는 이제 이 upsert 함수들로 "그 학생의 그 줄 하나"만 찾아 고치거나 한 줄만
// 추가한다. 전체 재계산·정렬·레거시 폼응답 반영은 5분 트리거(runScheduledAttendance)와
// QR 메뉴의 수동 갱신에서만 돈다 - 둘 다 사용자가 응답을 기다리는 요청이 아니므로 느려도 된다.
// ─────────────────────────────────────────────

// 출결결과에 한 번에 여러 줄을 쓸 때(특히 안드로이드 "시트 전송" 일괄 전송) 매번 시트 전체를
// 다시 읽지 않도록, 행 위치를 한 번만 인덱싱해서 이 호출 동안 재사용한다.
function createResultUpserter_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var resultSheet = ss.getSheetByName('출결결과');
  if (!resultSheet) throw new Error('출결결과 시트 없음');

  var resultHeader = ['실제날짜', '신청월', '요일', '타임', '학번', '이름', '반', '소속', '참여구분', '제출시간', '출결', '입실상태', '차이시간(분)', '비고'];
  if (resultSheet.getLastRow() < 1) {
    resultSheet.getRange(1, 1, 1, resultHeader.length).setValues([resultHeader]);
  }

  var masterMap = buildStudentMasterMap_();
  var timeMap = buildTimeMap_();
  var rowIndex = {};
  var lastRow = resultSheet.getLastRow();
  if (lastRow >= 2) {
    var data = resultSheet.getRange(2, 1, lastRow - 1, 5).getValues();
    var duplicateRows = [];
    for (var i = 0; i < data.length; i++) {
      var key = formatDateKey(data[i][0]) + '|' + String(data[i][3] || '').trim() + '|' + String(data[i][4] || '').trim();
      var rowNum = i + 2;
      if (rowIndex[key] !== undefined) {
        // 같은 (날짜,타임,학번) 줄이 이미 있다 - 동시 저장 등으로 생긴 중복이다.
        // 더 아래(나중에 쓰인) 줄을 최신으로 보고, 위에 있던 줄은 지운다.
        duplicateRows.push(rowIndex[key]);
      }
      rowIndex[key] = rowNum;
    }
    // 중복 줄은 뒤(행 번호가 큰 것)부터 지워야 앞 줄 번호가 안 꼬인다.
    duplicateRows.sort(function (a, b) { return b - a; });
    duplicateRows.forEach(function (rowNum) {
      resultSheet.deleteRow(rowNum);
      Object.keys(rowIndex).forEach(function (k) {
        if (rowIndex[k] > rowNum) rowIndex[k] -= 1;
      });
    });
  }
  var nextFreeRow = resultSheet.getLastRow() + 1;

  return {
    // 한 학생의 한 (날짜,타임) 출결을 기록한다. 이미 그 줄이 있으면 덮어쓰고, 없으면 한 줄만 추가한다.
    upsert: function (dateKey, period, studentId, status, note, timestamp) {
      var master = masterMap[studentId] || {};
      var month = dateKey.substring(0, 7);
      var day = dayOfWeekKo_(parseDateKey(dateKey));
      var qrLike = (status === '출석' || status === '지각') ? { timestamp: timestamp } : null;
      var timingInfo = getTimingInfo(qrLike, day, period, timeMap);
      var submitTimeText = timestamp ? formatDateTimeKo(timestamp) : '';
      var row = [
        parseDateKey(dateKey), month, day, period, studentId,
        master.name || '', master.className || '', master.affiliation || '',
        '자율학습', submitTimeText, status, timingInfo.status, timingInfo.minutes, note || ''
      ];

      var key = dateKey + '|' + period + '|' + studentId;
      var targetRow = rowIndex[key];
      if (!targetRow) {
        targetRow = nextFreeRow;
        rowIndex[key] = targetRow;
        nextFreeRow++;
      }
      resultSheet.getRange(targetRow, 1, 1, row.length).setValues([row]);
      resultSheet.getRange(targetRow, 1, 1, 1).setNumberFormat('yyyy. m. d');
      resultSheet.getRange(targetRow, 2, 1, 1).setNumberFormat('@STRING@');
      resultSheet.getRange(targetRow, 5, 1, 1).setNumberFormat('@STRING@');

      // 결석이면, 담임이 아직 확인 전인 '미확인(N)' 행을 담임허락체크에 미리 만들어 둔다.
      // 이미 승인/반려 기록이 있으면 손대지 않는다(markAbsentByTeacher가 직접 Y로 올리는 경우 제외).
      if (status === '결석') {
        ensurePendingApprovalRow_(dateKey, period, studentId, master.name || '', master.className || '', '자율학습');
      }
    },
    // '리셋' 등으로 기록을 지울 때 쓴다.
    remove: function (dateKey, period, studentId) {
      var key = dateKey + '|' + period + '|' + studentId;
      var targetRow = rowIndex[key];
      if (!targetRow) return;
      resultSheet.deleteRow(targetRow);
      delete rowIndex[key];
      Object.keys(rowIndex).forEach(function (k) {
        if (rowIndex[k] > targetRow) rowIndex[k] -= 1;
      });
      nextFreeRow -= 1;
    }
  };
}

// 담임허락체크 시트를 출결결과의 '결석' 행에 맞춰 동기화한다. 기존에 저장된 담임허락(Y/N)과
// 사유는 그대로 보존한다.
function updateTeacherApprovalCheck() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var resultSheet = ss.getSheetByName('출결결과');
  var checkSheet = ss.getSheetByName('담임허락체크');
  if (!resultSheet) { throw new Error('출결결과 시트 없음'); }
  if (!checkSheet) { checkSheet = ss.insertSheet('담임허락체크'); }

  var header = ['날짜', '신청월', '반', '요일', '타임', '학번', '이름', '참여구분', '담임허락', '사유', '확인시간'];
  checkSheet.getRange(1, 1, 1, header.length).setValues([header]);

  var resultValues = resultSheet.getDataRange().getValues();
  var checkValues = checkSheet.getDataRange().getValues();

  var currentAbsenceMap = {};
  for (var i = 1; i < resultValues.length; i++) {
    var row = resultValues[i];
    var actualDate = row[0], period = String(row[3] || '').trim(), studentId = String(row[4] || '').trim();
    var attendance = String(row[10] || '').trim();
    if (!actualDate || !period || !studentId) continue;
    if (attendance !== '결석') continue;
    currentAbsenceMap[formatDateKey(actualDate) + '|' + period + '|' + studentId] = true;
  }

  var preservedRows = [];
  for (var i = 1; i < checkValues.length; i++) {
    var row = checkValues[i];
    var dateRaw = row[0], period = String(row[4] || '').trim(), studentId = String(row[5] || '').trim();
    var approval = String(row[8] || '').trim(), reason = row[9] || '', checkedAt = row[10] || '';
    if (!dateRaw || !period || !studentId) continue;
    var dateKey = formatDateKey(dateRaw);
    var key = dateKey + '|' + period + '|' + studentId;
    if (approval === 'Y' || currentAbsenceMap[key]) {
      preservedRows.push([parseDateKey(dateKey), normalizeMonth(row[1]), String(row[2] || '').trim(), String(row[3] || '').trim(), period, studentId, String(row[6] || '').trim(), String(row[7] || '').trim(), approval || 'N', reason, checkedAt]);
    }
  }

  var existingMap = {};
  preservedRows.forEach(function (row) { existingMap[formatDateKey(row[0]) + '|' + row[4] + '|' + row[5]] = true; });

  var rowsToAppend = [];
  for (var i = 1; i < resultValues.length; i++) {
    var row = resultValues[i];
    var actualDate = row[0], month = normalizeMonth(row[1]), day = String(row[2] || '').trim(), period = String(row[3] || '').trim();
    var studentId = String(row[4] || '').trim(), name = String(row[5] || '').trim(), className = String(row[6] || '').trim();
    var type = String(row[8] || '').trim(), attendance = String(row[10] || '').trim();
    if (!actualDate || !period || !studentId) continue;
    if (attendance !== '결석') continue;
    var dateKey = formatDateKey(actualDate);
    var key = dateKey + '|' + period + '|' + studentId;
    if (existingMap[key]) continue;
    rowsToAppend.push([parseDateKey(dateKey), month, className, day, period, studentId, name, type, 'N', '', '']);
    existingMap[key] = true;
  }

  var finalRows = preservedRows.concat(rowsToAppend);
  finalRows.sort(function (a, b) {
    var da = new Date(a[0]).getTime(), db = new Date(b[0]).getTime();
    if (da !== db) return da - db;
    if (String(a[2]) !== String(b[2])) return String(a[2]).localeCompare(String(b[2]), 'ko');
    if (String(a[4]) !== String(b[4])) return String(a[4]).localeCompare(String(b[4]), 'ko');
    return String(a[5]).localeCompare(String(b[5]), 'ko');
  });

  checkSheet.clearContents();
  checkSheet.getRange(1, 1, 1, header.length).setValues([header]);
  if (finalRows.length > 0) {
    checkSheet.getRange(2, 1, finalRows.length, 11).setValues(finalRows);
    checkSheet.getRange(2, 1, finalRows.length, 1).setNumberFormat('yyyy. m. d');
    checkSheet.getRange(2, 2, finalRows.length, 1).setNumberFormat('@STRING@');
    var rule = SpreadsheetApp.newDataValidation().requireValueInList(['Y', 'N'], true).setAllowInvalid(false).build();
    checkSheet.getRange(2, 9, finalRows.length, 1).setDataValidation(rule);
  }
}

// 학생이 NFC학생현황에 등록된 학번인지만 확인한다 — 월/날짜/신청 여부와 무관하게 출석 가능.
function checkWebAttendanceAllowed(studentId) {
  var masterMap = buildStudentMasterMap_();
  var cleanId = cleanStudentId(studentId);
  if (masterMap[cleanId]) return { allowed: true, message: '출석 가능' };
  return { allowed: false, message: '등록되지 않은 학번입니다. 학번을 다시 확인하세요.' };
}

// 담임이 Teacher.html 또는 안드로이드 앱에서 직접 결석을 지정한다. 더 이상 결석을 자동으로
// 추론하지 않으므로, 이 함수(또는 학생의 '사유 제출')를 통해서만 결석 기록이 생긴다.
// 담임이 직접 처리한 결석은 생성 즉시 담임허락을 'Y'로 승인 처리한다.
function markAbsentByTeacher(dateText, period, className, studentId, reason) {
  try {
    return withAttendanceLock_(function () {
      var masterMap = buildStudentMasterMap_();
      var cleanId = cleanStudentId(studentId);
      var info = masterMap[cleanId];
      if (!info) return { result: 'fail', message: '등록되지 않은 학번입니다: ' + studentId };

      var sheet = getAttendanceSheet_();
      var dateKey = normalizeDateText(dateText);
      var day = dayOfWeekKo_(parseDateKey(dateKey));
      var month = dateKey.substring(0, 7);
      var p = String(period || '').trim();

      var now = new Date();
      deletePreexistingAttendanceRow_(sheet, dateKey, p, cleanId);
      sheet.appendRow([now, cleanId, month, day, p, dateKey, '교사결석', String(reason || '').trim()]);

      var writer = createResultUpserter_();
      writer.upsert(dateKey, p, cleanId, '결석', String(reason || '').trim(), now);
      upsertApprovalRowApproved_(dateKey, p, cleanId, info.name, info.className, '자율학습', reason);

      return { result: 'success', message: info.name + ' 학생을 결석 처리했습니다.' };
    });
  } catch (error) {
    return { result: 'fail', message: error.toString() };
  }
}

// 결석 행이 담임허락체크에 아직 없을 때만 '미확인(N)' 상태로 한 줄 추가한다. 이미 있으면
// (교사가 이미 처리했든, 담임이 승인/반려했든) 기존 상태를 건드리지 않는다.
// 같은 (날짜,타임,학번)에 해당하는 줄을 찾는다. 혹시 중복으로 여러 줄이 있으면(동시 저장 등)
// 가장 나중 줄만 남기고 앞의 것들은 지운 뒤, 남은 줄의 행 번호를 돌려준다. 없으면 null.
function findApprovalRowKeepingLatest_(checkSheet, dateKey, period, studentId) {
  var lastRow = checkSheet.getLastRow();
  if (lastRow < 2) return null;
  var data = checkSheet.getRange(2, 1, lastRow - 1, 6).getValues();
  var matches = [];
  for (var i = 0; i < data.length; i++) {
    if (formatDateKey(data[i][0]) === dateKey && String(data[i][4]).trim() === period && cleanStudentId(data[i][5]) === studentId) {
      matches.push(i + 2);
    }
  }
  if (matches.length === 0) return null;
  var keepRow = matches.pop();
  matches.sort(function (a, b) { return b - a; }); // 뒤에서부터 지워야 행 번호가 안 꼬인다
  matches.forEach(function (rowNum) {
    checkSheet.deleteRow(rowNum);
    if (rowNum < keepRow) keepRow -= 1;
  });
  return keepRow;
}

function ensurePendingApprovalRow_(dateKey, period, studentId, name, className, type) {
  var checkSheet = getApprovalSheet();
  if (findApprovalRowKeepingLatest_(checkSheet, dateKey, period, studentId) !== null) return;
  appendApprovalRow_(checkSheet, dateKey, period, studentId, name, className, type, 'N', '', '');
}

// 담임이 직접 결석 처리를 한 경우: 이미 있는 행이면 바로 Y로 승인, 없으면 Y로 승인된 채로 새로 만든다.
function upsertApprovalRowApproved_(dateKey, period, studentId, name, className, type, reason) {
  var checkSheet = getApprovalSheet();
  var keepRow = findApprovalRowKeepingLatest_(checkSheet, dateKey, period, studentId);
  if (keepRow !== null) {
    checkSheet.getRange(keepRow, 9).setValue('Y');
    checkSheet.getRange(keepRow, 10).setValue(String(reason || '').trim());
    checkSheet.getRange(keepRow, 11).setValue(new Date());
    return;
  }
  appendApprovalRow_(checkSheet, dateKey, period, studentId, name, className, type, 'Y', reason, new Date());
}

function appendApprovalRow_(checkSheet, dateKey, period, studentId, name, className, type, approval, reason, checkedAt) {
  var day = dayOfWeekKo_(parseDateKey(dateKey));
  var month = dateKey.substring(0, 7);
  var row = [parseDateKey(dateKey), month, className, day, period, studentId, name, type, approval, String(reason || '').trim(), checkedAt || ''];
  var newRowIndex = checkSheet.getLastRow() < 1 ? 2 : checkSheet.getLastRow() + 1;
  checkSheet.getRange(newRowIndex, 1, 1, row.length).setValues([row]);
  checkSheet.getRange(newRowIndex, 1, 1, 1).setNumberFormat('yyyy. m. d');
  checkSheet.getRange(newRowIndex, 2, 1, 1).setNumberFormat('@STRING@');
}

function deletePreexistingAttendanceRow_(sheet, dateText, period, studentId) {
  var data = sheet.getDataRange().getValues();
  for (var i = data.length - 1; i >= 1; i--) {
    if (normalizeDateText(data[i][5]) === dateText && String(data[i][4]).trim() === period && cleanStudentId(data[i][1]) === studentId) {
      sheet.deleteRow(i + 1);
    }
  }
}

// NFC_Integration.gs / Code.gs에서 쓰던 기존 이름을 그대로 유지한다.
function deletePreexistingRow(sheet, dateText, period, studentId) {
  deletePreexistingAttendanceRow_(sheet, dateText, period, studentId);
}

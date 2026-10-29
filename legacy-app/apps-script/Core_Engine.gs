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

// syncAttendance()는 이제 명단 매칭 없이 웹앱응답/폼응답을 그대로 옮기는 가벼운 변환이라 매번
// 동기 호출해도 무겁지 않지만, 혹시 실패하더라도 "출결 기록 자체는 이미 저장됐다"는 사실과
// "집계 갱신에 실패했다"는 사실을 분리해서 알 수 있도록 감싼다. (과거 버그: 저장은 이미 끝났는데
// syncAttendance() 내부에서 예외가 나면 저장 자체가 실패한 것처럼 보이던 문제를 고친다.)
function syncAttendanceSafely_() {
  try {
    syncAttendance();
    return { success: true };
  } catch (error) {
    return { success: false, message: error.toString() };
  }
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
    var masterMap = buildStudentMasterMap_();
    var cleanId = cleanStudentId(studentId);
    var info = masterMap[cleanId];
    if (!info) return { result: 'fail', message: '등록되지 않은 학번입니다: ' + studentId };

    var sheet = getAttendanceSheet_();
    var dateKey = normalizeDateText(dateText);
    var day = dayOfWeekKo_(parseDateKey(dateKey));
    var month = dateKey.substring(0, 7);
    var p = String(period || '').trim();

    deletePreexistingAttendanceRow_(sheet, dateKey, p, cleanId);
    sheet.appendRow([new Date(), cleanId, month, day, p, dateKey, '교사결석', String(reason || '').trim()]);

    var syncResult = syncAttendanceSafely_();
    approveTeacherAbsence_(dateKey, p, cleanId, reason);

    if (!syncResult.success) {
      return { result: 'success', message: info.name + ' 학생을 결석 처리했지만 집계 갱신 중 오류가 있었습니다: ' + syncResult.message };
    }
    return { result: 'success', message: info.name + ' 학생을 결석 처리했습니다.' };
  } catch (error) {
    return { result: 'fail', message: error.toString() };
  }
}

function approveTeacherAbsence_(dateKey, period, studentId, reason) {
  var checkSheet = getApprovalSheet();
  var data = checkSheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    if (formatDateKey(data[i][0]) === dateKey && String(data[i][4]).trim() === period && cleanStudentId(data[i][5]) === studentId) {
      checkSheet.getRange(i + 1, 9).setValue('Y');
      checkSheet.getRange(i + 1, 10).setValue(String(reason || '').trim());
      checkSheet.getRange(i + 1, 11).setValue(new Date());
      return;
    }
  }
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

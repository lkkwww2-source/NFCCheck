// [NFC_Integration.gs] 안드로이드 앱 통신 - 학생 명부 + 출결 기록 저장/조회
// ─────────────────────────────────────────────
// NFC학생현황 시트가 유일한 명부다. 참여명단입력(N월)/요일별 신청 개념은 모두 제거했고,
// 안드로이드 앱의 HOME 화면도 요일별 신청 명단이 아니라 전체 학생 명단을 그대로 보여준다.
// ─────────────────────────────────────────────

function getStudentListForAndroid() {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName('NFC학생현황');
    if (!sheet) return { result: 'fail', message: 'NFC학생현황 시트 없음' };

    var data = sheet.getDataRange().getValues();
    var students = [];
    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      var studentId = cleanStudentId(row[0]);
      var name = String(row[1] || '').trim();
      var nfcId = String(row[2] || '').trim();
      if (!studentId || !name) continue;
      students.push({ studentId: studentId, name: name, nfcId: nfcId, className: classNameFromStudentId_(studentId) });
    }
    students.sort(function (a, b) {
      if (a.className !== b.className) return a.className.localeCompare(b.className, 'ko');
      return a.studentId.localeCompare(b.studentId, 'ko');
    });
    return { result: 'success', mode: 'nfc-list', data: students };
  } catch (e) {
    return { result: 'fail', message: e.toString() };
  }
}

// 안드로이드 앱이 GET_MASTER로 부르는 이름을 그대로 유지한다.
// (예전: 요일별 신청 명단을 돌려줬음 / 지금: NFC학생현황 전체 명단을 그대로 돌려준다)
function getWeeklyRosterFullData() {
  return getStudentListForAndroid();
}

// Code.gs의 GETSTUDENTLIST, 메뉴 점검 등에서 쓰던 이름을 그대로 유지한다.
function getStudentList() {
  return getStudentListForAndroid();
}

function addStudent(data) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName('NFC학생현황');
    if (!sheet) return { result: 'fail', message: 'NFC학생현황 시트 없음' };

    var studentId = cleanStudentId(data.studentId);
    var name = String(data.name || '').trim();
    var nfcId = String(data.nfcId || '').trim();
    if (!studentId || !name) return { result: 'fail', message: '학번 또는 이름이 비어있음' };

    var existing = sheet.getDataRange().getValues();
    for (var i = 1; i < existing.length; i++) {
      if (cleanStudentId(existing[i][0]) === studentId) {
        return { result: 'fail', message: '이미 등록된 학번입니다: ' + studentId };
      }
    }
    sheet.appendRow([studentId, name, nfcId, '']);
    return { result: 'success', message: name + ' 학생이 등록되었습니다.' };
  } catch (e) {
    return { result: 'fail', message: e.toString() };
  }
}

function deleteStudent(studentId) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName('NFC학생현황');
    if (!sheet) return { result: 'fail', message: 'NFC학생현황 시트 없음' };
    var data = sheet.getDataRange().getValues();
    for (var i = data.length - 1; i >= 1; i--) {
      if (cleanStudentId(data[i][0]) === cleanStudentId(studentId)) {
        sheet.deleteRow(i + 1);
        return { result: 'success', message: '삭제 완료' };
      }
    }
    return { result: 'fail', message: '해당 학번을 찾을 수 없음' };
  } catch (e) {
    return { result: 'fail', message: e.toString() };
  }
}

function updateStudentNfc(studentId, nfcId) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName('NFC학생현황');
    if (!sheet) return { result: 'fail', message: 'NFC학생현황 시트 없음' };

    var data = sheet.getDataRange().getValues();
    for (var i = 1; i < data.length; i++) {
      if (cleanStudentId(data[i][0]) === cleanStudentId(studentId)) {
        sheet.getRange(i + 1, 3).setValue(nfcId);
        return { result: 'success', message: 'NFC 등록 완료: ' + nfcId };
      }
    }
    return { result: 'fail', message: '해당 학번을 찾을 수 없음: ' + studentId };
  } catch (e) {
    return { result: 'fail', message: e.toString() };
  }
}

// 오늘 하루치 출결 기록 (앱 실행/새로고침시 상태 복원용)
function getTodayAttendanceStatus() {
  var now = new Date();
  var todayText = Utilities.formatDate(now, Session.getScriptTimeZone(), 'yyyy-MM-dd');
  return getAttendanceStatusByDate(todayText);
}

// 특정 날짜의 출결 기록 조회 - 안드로이드 앱에서 날짜를 바꿀 때마다 호출한다.
// (과거 버그: 안드로이드 앱이 날짜를 서버에 전혀 보내지 않아서, 지난 날짜를 조회/정정해도
// 항상 "오늘" 기준으로만 처리됐다. 이제 앱이 직접 고른 날짜를 그대로 주고받는다.)
function getAttendanceStatusByDate(dateText) {
  try {
    var sheet = getAttendanceSheet_();
    if (sheet.getLastRow() < 2) return { result: 'success', data: [] };

    var target = normalizeDateText(dateText);
    var data = sheet.getDataRange().getValues();
    var seen = {};

    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      if (normalizeDateText(row[5]) !== target) continue;

      var studentNum = cleanStudentId(row[1]);
      var period = String(row[4] || '').trim();
      var inputMethod = String(row[6] || '').trim();
      var status = decodeAttendanceStatus_(inputMethod);
      if (!studentNum || !period || !status) continue;

      var slotName = period === '1타임' ? '방과후' : (period === '2타임' ? '야간1' : '야간2');
      var key = studentNum + '|' + period;
      seen[key] = {
        studentNum: studentNum,
        slotName: slotName,
        status: status,
        recordTime: formatDateTimeKo(row[0]).split(' ').pop(),
        inputType: String(inputMethod).replace(/(결석|지각|학사|출석)$/, '')
      };
    }

    return { result: 'success', data: Object.keys(seen).map(function (k) { return seen[k]; }) };
  } catch (e) {
    return { result: 'fail', message: e.toString() };
  }
}

// records: [{ studentNum, slotName('방과후'|'야간1'|'야간2'), status('출석'|'지각'|'결석'|'학사'|'리셋'),
//             date('yyyy-MM-dd'), recordTime('HH:mm:ss'), inputType('NFC'|'수동') }]
//
// ⚠️ date/recordTime은 반드시 클라이언트(안드로이드 앱)가 보낸 값을 그대로 쓴다 — 서버의
// '지금'으로 다시 계산하지 않는다. (과거 버그: 항상 서버 now()를 써서 저장했기 때문에, 나중에
// 지난 날짜를 터치로 정정 입력해도 항상 "오늘·지금"으로 기록되어 통계에서 빠지던 문제를 고친다.)
function saveAndroidAttendance(records) {
  try {
    var sheet = getAttendanceSheet_();
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      var studentId = cleanStudentId(rec.studentNum);
      var dateText = normalizeDateText(rec.date);
      var slotName = String(rec.slotName || '').trim();
      var period = slotName === '방과후' ? '1타임' : (slotName === '야간1' ? '2타임' : '3타임');
      var status = String(rec.status || '').trim();
      var recordTime = String(rec.recordTime || '00:00:00').trim();
      if (!studentId || !dateText || !status) continue;

      deletePreexistingAttendanceRow_(sheet, dateText, period, studentId);
      if (status === '리셋') continue; // 리셋은 기존 행만 지우고 새로 쓰지 않음

      var month = dateText.substring(0, 7);
      var day = dayOfWeekKo_(parseDateKey(dateText));
      var timestamp = new Date(dateText + 'T' + recordTime);
      var inputMethod = encodeAttendanceMethod_(rec.inputType, status);
      sheet.appendRow([timestamp, studentId, month, day, period, dateText, inputMethod, '']);
    }
  } catch (error) {
    return { result: 'fail', message: error.toString() };
  }

  // 원본 기록은 이미 저장됐으므로, 집계(출결결과) 갱신이 실패해도 저장 자체를 실패로 보고하지 않는다.
  var syncResult = syncAttendanceSafely_();
  if (!syncResult.success) {
    return { result: 'success', message: '기록은 저장되었지만 집계 갱신 중 오류가 있었습니다: ' + syncResult.message };
  }
  return { result: 'success', message: '동기화 성공' };
}

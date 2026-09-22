// [NFC_Integration.gs] 학생 명부 관리 + 출결 기록 저장/조회
// ─────────────────────────────────────────────
// NFC학생현황 시트가 유일한 학생 명부다. 더 이상 사전 신청/참여명단/구글폼(QR) 개념이
// 없으므로, 이 명부에 있는 학생이면 누구든 날짜·타임에 상관없이 자유롭게 출결을 기록한다.
// ─────────────────────────────────────────────

function getStudentMasterList_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("NFC학생현황");
  if (!sheet) return [];

  var data = sheet.getDataRange().getValues();
  var students = [];
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var studentId = cleanStudentId(row[0]);
    var name = String(row[1] || "").trim();
    var nfcId = String(row[2] || "").trim();
    var className = String(row[3] || "").trim();
    if (!studentId || !name) continue;
    if (className && className.indexOf('반') === -1) className += '반';
    students.push({ studentId: studentId, name: name, nfcId: nfcId, className: className });
  }
  students.sort(function (a, b) {
    if (a.className !== b.className) return a.className.localeCompare(b.className, 'ko');
    return a.studentId.localeCompare(b.studentId, 'ko');
  });
  return students;
}

// 📋 안드로이드 명부 조회 (HOME 탭 roster / 학생관리 탭 공용)
function getStudentListForAndroid() {
  try {
    return { result: "success", data: getStudentMasterList_() };
  } catch (e) {
    return { result: "fail", message: e.toString() };
  }
}

function addStudent(data) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName("NFC학생현황");
    if (!sheet) return { result: "fail", message: "NFC학생현황 시트 없음" };

    var studentId = cleanStudentId(data.studentId);
    var name = String(data.name || "").trim();
    var nfcId = String(data.nfcId || "").trim();
    var className = String(data.className || "").trim();

    if (!studentId || !name) return { result: "fail", message: "학번 또는 이름이 비어있음" };

    var existing = sheet.getDataRange().getValues();
    for (var i = 1; i < existing.length; i++) {
      if (cleanStudentId(existing[i][0]) === studentId) {
        return { result: "fail", message: "이미 등록된 학번입니다: " + studentId };
      }
    }

    sheet.appendRow([studentId, name, nfcId, className]);
    return { result: "success", message: name + " 학생이 등록되었습니다." };
  } catch (e) {
    return { result: "fail", message: e.toString() };
  }
}

function deleteStudent(studentId) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName("NFC학생현황");
    if (!sheet) return { result: "fail", message: "NFC학생현황 시트 없음" };
    var data = sheet.getDataRange().getValues();
    for (var i = data.length - 1; i >= 1; i--) {
      if (cleanStudentId(data[i][0]) === cleanStudentId(studentId)) {
        sheet.deleteRow(i + 1);
        return { result: "success", message: "삭제 완료" };
      }
    }
    return { result: "fail", message: "해당 학번을 찾을 수 없음" };
  } catch (e) {
    return { result: "fail", message: e.toString() };
  }
}

function updateStudentNfc(studentId, nfcId) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName("NFC학생현황");
    if (!sheet) return { result: "fail", message: "NFC학생현황 시트 없음" };

    var data = sheet.getDataRange().getValues();
    for (var i = 1; i < data.length; i++) {
      if (cleanStudentId(data[i][0]) === cleanStudentId(studentId)) {
        sheet.getRange(i + 1, 3).setValue(nfcId);
        return { result: "success", message: "NFC 등록 완료: " + nfcId };
      }
    }
    return { result: "fail", message: "해당 학번을 찾을 수 없음: " + studentId };
  } catch (e) {
    return { result: "fail", message: e.toString() };
  }
}

// ─────────────────────────────────────────────
// 출결결과 시트: 신청/명단 매칭 없이 NFC 태깅·수동 체크로 들어오는 기록을 그대로 적재하는 로그.
// 헤더: 날짜, 요일, 타임, 학번, 이름, 반, 상태, 기록시각, 입력방식, 갱신시각
// 같은 (날짜, 타임, 학번) 조합이 다시 들어오면 기존 행을 지우고 새로 써서 "수정"을 지원한다.
// → 날짜를 과거로 지정해서 보내면 지난 기록도 그대로 정정할 수 있다.
// ─────────────────────────────────────────────
function getAttendanceSheet_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName('출결결과');
  var header = ['날짜', '요일', '타임', '학번', '이름', '반', '상태', '기록시각', '입력방식', '갱신시각'];
  if (!sheet) {
    sheet = ss.insertSheet('출결결과');
    sheet.getRange(1, 1, 1, header.length).setValues([header]);
  }
  return sheet;
}

function deletePreexistingAttendanceRow_(sheet, dateText, slotName, studentId) {
  var data = sheet.getDataRange().getValues();
  for (var i = data.length - 1; i >= 1; i--) {
    if (normalizeDateText(data[i][0]) === dateText &&
        String(data[i][2]).trim() === slotName &&
        cleanStudentId(data[i][3]) === studentId) {
      sheet.deleteRow(i + 1);
    }
  }
}

// records: [{ studentNum, name, className, slotName, status, date(yyyy-MM-dd),
//             recordTime(HH:mm:ss), inputType('NFC'|'수동') }]
function saveAndroidAttendance(records) {
  try {
    var sheet = getAttendanceSheet_();
    var now = new Date();
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      var studentId = cleanStudentId(rec.studentNum);
      var dateText = normalizeDateText(rec.date);
      var slotName = String(rec.slotName || '').trim();
      var status = String(rec.status || '').trim();
      var recordTime = String(rec.recordTime || '').trim();

      if (!studentId || !dateText || !slotName || !status) continue;

      deletePreexistingAttendanceRow_(sheet, dateText, slotName, studentId);

      if (status === '리셋') continue; // 리셋은 기존 행만 지우고 새로 쓰지 않음

      var dateObj = parseDateKey(dateText);
      var day = dayOfWeekKo(dateText);
      sheet.appendRow([
        dateObj, day, slotName, studentId, rec.name || '', rec.className || '',
        status, recordTime, rec.inputType || 'NFC', now
      ]);
    }
    return { result: "success", message: "동기화 성공" };
  } catch (error) {
    return { result: "fail", message: error.toString() };
  }
}

// 특정 날짜의 출결 기록을 조회 (앱에서 날짜를 바꿀 때마다 호출 → 해당 날짜 상태를 그대로 불러옴)
function getAttendanceByDate(dateText) {
  try {
    var sheet = getAttendanceSheet_();
    if (sheet.getLastRow() < 2) return { result: "success", data: [] };

    var target = normalizeDateText(dateText);
    var data = sheet.getDataRange().getValues();
    var list = [];
    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      if (normalizeDateText(row[0]) !== target) continue;
      list.push({
        studentNum: cleanStudentId(row[3]),
        slotName: String(row[2] || '').trim(),
        status: String(row[6] || '').trim(),
        recordTime: String(row[7] || '').trim(),
        inputType: String(row[8] || '').trim()
      });
    }
    return { result: "success", data: list };
  } catch (error) {
    return { result: "fail", message: error.toString() };
  }
}

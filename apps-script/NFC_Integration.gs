// [NFC_Integration.gs] 학생 명부 관리 + 출결 기록 저장/조회
// ─────────────────────────────────────────────
// NFC학생현황 시트가 유일한 학생 명부다. 더 이상 사전 신청/참여명단/구글폼(QR) 개념이
// 없으므로, 이 명부에 있는 학생이면 누구든 날짜·타임에 상관없이 자유롭게 출결을 기록한다.
// ─────────────────────────────────────────────

// NFC학생현황 시트의 D열은 '반'이 아니라 '등록여부'라서(현재 앱에서는 사용하지 않음),
// 반은 학번 자릿수로 계산한다: 1번째 자리=학년, 4자리 학번은 다음 1자리, 5자리 이상은 다음 2자리가 반.
// (Board_Handlers.gs의 inferGradeFromStudentId_ 와 같은 학번 규칙을 쓴다)
function classNameFromStudentId_(studentId) {
  var id = String(studentId || '').trim();
  if (id.length === 4) return id.substring(1, 2) + '반';
  if (id.length >= 5) return id.substring(1, 3).replace(/^0/, '') + '반';
  return '';
}

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
    // row[3](등록여부)은 반과 무관하므로 읽지 않는다.
    var className = classNameFromStudentId_(studentId);
    if (!studentId || !name) continue;
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
    // data.className은 안드로이드 앱이 참고용으로 보내지만, 반은 학번으로 계산하므로 저장하지 않는다.
    // D열(등록여부)은 건드리지 않고 비워둔다.

    if (!studentId || !name) return { result: "fail", message: "학번 또는 이름이 비어있음" };

    var existing = sheet.getDataRange().getValues();
    for (var i = 1; i < existing.length; i++) {
      if (cleanStudentId(existing[i][0]) === studentId) {
        return { result: "fail", message: "이미 등록된 학번입니다: " + studentId };
      }
    }

    sheet.appendRow([studentId, name, nfcId, '']);
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
// 웹앱응답 시트: 기존에 누적돼 있던 출결 기록과 같은 포맷(A~H)을 그대로 이어서 쓴다.
// 헤더: 타임스탬프, 학번, 신청월, 요일, 타임, 실제날짜, 입력방식, 사전내용
//   - 타임스탬프: 실제날짜 + 기록시각을 합친 시각 (NFC=실제 태깅 시각, 수동/전자칠판=타임 시작시각)
//   - 타임: "1타임"/"2타임"/"3타임" (기존 데이터와 동일한 표기. 안드로이드 앱/전자칠판 UI에는
//     "방과후"/"야간1"/"야간2" 같은 사람이 읽기 쉬운 이름으로 보이지만 저장은 항상 이 값으로 한다)
//   - 입력방식: 기존처럼 방식+결과를 합쳐서 기록한다 (예: NFC출석, NFC지각, 수동결석, 전자칠판출석 등).
//     8컬럼 포맷에 별도 "상태" 칸이 없어서, 상태는 이 컬럼 값의 접미사로 판별한다.
//   - 사전내용: 더 이상 사유 제출 기능이 없으므로 항상 빈 값으로 남겨둔다 (기존 출석 기록 행과 동일).
// 같은 (실제날짜, 타임, 학번) 조합이 다시 들어오면 기존 행을 지우고 새로 써서 "수정"을 지원한다.
// → 날짜를 과거로 지정해서 보내면 지난 기록도 그대로 정정할 수 있다.
// ─────────────────────────────────────────────
var ATTENDANCE_STATUS_KEYWORDS_ = ['결석', '지각', '학사', '출석'];

function encodeInputMethod_(inputType, status) {
  return String(inputType || '') + String(status || '');
}

function decodeStatusFromInputMethod_(value) {
  var text = String(value || '');
  for (var i = 0; i < ATTENDANCE_STATUS_KEYWORDS_.length; i++) {
    if (text.indexOf(ATTENDANCE_STATUS_KEYWORDS_[i]) !== -1) return ATTENDANCE_STATUS_KEYWORDS_[i];
  }
  return '';
}

function decodeMethodFromInputMethod_(value) {
  return String(value || '').replace(/(결석|지각|학사|출석)$/, '');
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

function deletePreexistingAttendanceRow_(sheet, dateText, slotName, studentId) {
  var data = sheet.getDataRange().getValues();
  for (var i = data.length - 1; i >= 1; i--) {
    if (normalizeDateText(data[i][5]) === dateText &&
        String(data[i][4]).trim() === slotName &&
        cleanStudentId(data[i][1]) === studentId) {
      sheet.deleteRow(i + 1);
    }
  }
}

// records: [{ studentNum, slotName('1타임'|'2타임'|'3타임'), status('출석'|'지각'|'결석'|'학사'|'리셋'),
//             date(yyyy-MM-dd), recordTime(HH:mm:ss), inputType('NFC'|'수동'|'전자칠판') }]
function saveAndroidAttendance(records) {
  try {
    var sheet = getAttendanceSheet_();
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      var studentId = cleanStudentId(rec.studentNum);
      var dateText = normalizeDateText(rec.date);
      var slotName = String(rec.slotName || '').trim();
      var status = String(rec.status || '').trim();
      var recordTime = String(rec.recordTime || '00:00:00').trim();

      if (!studentId || !dateText || !slotName || !status) continue;

      deletePreexistingAttendanceRow_(sheet, dateText, slotName, studentId);

      if (status === '리셋') continue; // 리셋은 기존 행만 지우고 새로 쓰지 않음

      var month = normalizeMonth(dateText);
      var day = dayOfWeekKo(dateText);
      var timestamp = new Date(dateText + 'T' + recordTime);
      var inputMethod = encodeInputMethod_(rec.inputType, status);

      sheet.appendRow([timestamp, studentId, month, day, slotName, dateText, inputMethod, '']);
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
      if (normalizeDateText(row[5]) !== target) continue;
      var inputMethod = String(row[6] || '').trim();
      list.push({
        studentNum: cleanStudentId(row[1]),
        slotName: String(row[4] || '').trim(),
        status: decodeStatusFromInputMethod_(inputMethod),
        recordTime: formatDateTimeKo(row[0]).split(' ').pop(),
        inputType: decodeMethodFromInputMethod_(inputMethod)
      });
    }
    return { result: "success", data: list };
  } catch (error) {
    return { result: "fail", message: error.toString() };
  }
}

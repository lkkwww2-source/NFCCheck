// [Code.gs] 웹앱 라우팅 및 안드로이드 NFC 중앙 통제 라우터
// ─────────────────────────────────────────────
// NFC학생현황 시트가 유일한 학생 명부다. 참여명단입력(N월) 사전신청 명단은 더 이상 쓰지 않으며,
// 월 구분 없이 NFC학생현황에 등록된 학생이면 바로 출결을 기록할 수 있다.
// ─────────────────────────────────────────────

function doGet(e) {
  if (!e || !e.parameter) {
    return HtmlService.createHtmlOutput("<h3>유효하지 않은 접근입니다.</h3>");
  }

  var mode = e.parameter.mode;

  // 📱 [NFC 앱 전용 API] 학생 명부 조회 (HOME 탭 / 학생관리 탭 공용)
  if (mode === "GET_MASTER") {
    return ContentService.createTextOutput(JSON.stringify(getStudentListForAndroid()))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 📱 [NFC 앱 전용 API] 오늘 출석 완료 기록 조회 (앱 재실행시 상태 복원용)
  if (mode === "GET_TODAY_STATUS") {
    return ContentService.createTextOutput(JSON.stringify(getTodayAttendanceStatus()))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 📱 [NFC 앱 전용 API] 특정 날짜의 출결 기록 조회 (날짜 변경 시 호출)
  if (mode === "GET_ATTENDANCE_BY_DATE") {
    return ContentService.createTextOutput(JSON.stringify(getAttendanceStatusByDate(e.parameter.date)))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 📱 [NFC 앱 전용 API] 담임 확인 탭 - 특정 날짜/학반의 결석 장부 실시간 조회
  if (mode === "GET_ATTENDANCE_LOGS") {
    return ContentService.createTextOutput(JSON.stringify(getAndroidAttendanceLogs(e.parameter.date, e.parameter.className)))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 📱 [NFC 앱 전용 API] 누적현황 탭 - 월별 통계 조회
  if (mode === "GET_MONTHLY_STATS") {
    return ContentService.createTextOutput(JSON.stringify(getMonthlyStatsForAndroid(e.parameter.month)))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 📱 [NFC 앱 전용 API] 학생관리 탭 - 학생 목록 조회
  if (mode === "GETSTUDENTLIST") {
    return ContentService.createTextOutput(JSON.stringify(getStudentListForAndroid()))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 🌐 [기존 QR 웹앱 브라우저 화면 라우팅]
  if (e.parameter.page === 'teacher') {
    return HtmlService.createHtmlOutputFromFile('Teacher').setTitle('담임 출결 확인');
  }
  if (e.parameter.page === 'view') {
    return HtmlService.createHtmlOutputFromFile('View').setTitle('담임 조회 화면');
  }
  if (e.parameter.page === 'grade') {
    return HtmlService.createHtmlOutputFromFile('Grade').setTitle('학년부장 출결 현황');
  }

  return HtmlService.createHtmlOutputFromFile('Index').setTitle('운호고 야자출결');
}

function doPost(e) {
  try {
    var requestData = JSON.parse(e.postData.contents);

    // 📱 [NFC 앱 전용 API] 앱에서 NFC 실시간 태깅/수동 처리시 출결 적재 관문
    if (requestData.mode === "SAVE_ATTENDANCE") {
      return ContentService.createTextOutput(JSON.stringify(saveAndroidAttendance(requestData.records)))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // 📱 [NFC 앱 전용 API] 앱에서 담임교사가 사유 선택시 원격 저장 관문
    if (requestData.mode === "UPDATE_REASON") {
      return ContentService.createTextOutput(JSON.stringify(updateAndroidAbsenceReason(requestData)))
        .setMimeType(ContentService.MimeType.JSON);
    }
    if (requestData.mode === "ADD_STUDENT") {
      return ContentService.createTextOutput(JSON.stringify(addStudent(requestData)))
        .setMimeType(ContentService.MimeType.JSON);
    }
    if (requestData.mode === "UPDATE_NFC") {
      return ContentService.createTextOutput(JSON.stringify(updateStudentNfc(requestData.studentId, requestData.nfcId)))
        .setMimeType(ContentService.MimeType.JSON);
    }
    if (requestData.mode === "DELETE_STUDENT") {
      return ContentService.createTextOutput(JSON.stringify(deleteStudent(requestData.studentId)))
        .setMimeType(ContentService.MimeType.JSON);
    }

  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({ result: "fail", message: error.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  }

  return ContentService.createTextOutput(JSON.stringify({ result: "success", message: "Bypass POST" }))
    .setMimeType(ContentService.MimeType.JSON);
}

// 학생용 QR/웹 자가출석. NFC학생현황에 등록된 학번이면 월/신청 여부와 무관하게 바로 출석 처리한다.
function submitWebAttendance(studentId) {
  var now = new Date();
  var info = getWebActualTimeInfo(now);
  var check = checkWebAttendanceAllowed(studentId);
  if (!check.allowed) {
    return {
      success: false, message: check.message,
      time: Utilities.formatDate(now, Session.getScriptTimeZone(), 'yyyy. M. d HH:mm:ss'),
      period: info.month + ' ' + info.day + ' ' + info.period
    };
  }
  var sheet = getAttendanceSheet_();
  var cleanId = cleanStudentId(studentId);
  deletePreexistingRow(sheet, info.dateText, info.period, cleanId);
  sheet.appendRow([now, cleanId, info.month, info.day, info.period, info.dateText, '웹앱출석', '']);
  createResultUpserter_().upsert(info.dateText, info.period, cleanId, '출석', '', now);
  return {
    success: true, message: '출석 완료', studentId: cleanId,
    time: Utilities.formatDate(now, Session.getScriptTimeZone(), 'yyyy. M. d HH:mm:ss'),
    period: info.month + ' ' + info.day + ' ' + info.period
  };
}

// 학생이 스스로 불참 사유를 사전 제출한다. 참여명단 대비 판정이 사라졌으므로, 이 제출이
// 바로 출결결과에 '결석'으로 기록되고 담임의 확인(사유 승인)을 기다리는 형태로 들어간다.
function submitWebReason(studentId, reasonDate, reasonPeriods, reasonText) {
  var sheet = getAttendanceSheet_();
  var cleanId = cleanStudentId(studentId);
  var d = new Date(reasonDate);
  var tz = Session.getScriptTimeZone();
  var dateText = Utilities.formatDate(d, tz, 'yyyy-MM-dd');
  var month = Utilities.formatDate(d, tz, 'yyyy-MM');
  var day = dayOfWeekKo_(d);
  var periods = Array.isArray(reasonPeriods) ? reasonPeriods : String(reasonPeriods).split(',');
  var now = new Date();
  var writer = createResultUpserter_();
  var trimmedReason = String(reasonText || '').trim();
  periods.forEach(function (period) {
    var p = String(period).trim();
    deletePreexistingRow(sheet, dateText, p, cleanId);
    sheet.appendRow([now, cleanId, month, day, p, dateText, '웹앱사유', trimmedReason]);
    writer.upsert(dateText, p, cleanId, '결석', trimmedReason, now);
  });
  return { success: true, message: '사유 제출 완료', period: month + ' ' + day + ' ' + periods.join(',') };
}

function checkTeacherPassword(pw) {
  return pw === 'hsm2026';
}

// [Code.gs] 안드로이드 NFC 출결 시스템 API 라우터
// 이 프로젝트는 이제 안드로이드 앱 전용 백엔드로만 동작한다 (QR/웹앱 화면 없음).

function doGet(e) {
  if (!e || !e.parameter) {
    return ContentService.createTextOutput('NFC 출결 시스템 API');
  }

  var mode = e.parameter.mode;

  // 📱 명부 조회 (HOME 탭 roster / 학생관리 탭 공용)
  if (mode === "GET_MASTER" || mode === "GETSTUDENTLIST") {
    return ContentService.createTextOutput(JSON.stringify(getStudentListForAndroid()))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 📱 특정 날짜의 출결 기록 조회 (날짜 변경 시 호출)
  if (mode === "GET_ATTENDANCE_BY_DATE") {
    return ContentService.createTextOutput(JSON.stringify(getAttendanceByDate(e.parameter.date)))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // 📱 누적현황(STATS) 탭 - 월별 통계
  if (mode === "GET_MONTHLY_STATS") {
    return ContentService.createTextOutput(JSON.stringify(getMonthlyStatsForAndroid(e.parameter.month)))
      .setMimeType(ContentService.MimeType.JSON);
  }

  return ContentService.createTextOutput('Unknown mode');
}

function doPost(e) {
  try {
    var requestData = JSON.parse(e.postData.contents);

    // 📱 NFC 태깅 / 수동 체크 결과 저장
    if (requestData.mode === "SAVE_ATTENDANCE") {
      return ContentService.createTextOutput(JSON.stringify(saveAndroidAttendance(requestData.records)))
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

  return ContentService.createTextOutput(JSON.stringify({ result: "fail", message: "Unknown mode" }))
    .setMimeType(ContentService.MimeType.JSON);
}

// [QR_Menu.gs] QR/NFC 야자 출결 시스템 상단 메뉴 및 관리 화면
// clasp로 내려받은 프로젝트에 이 파일을 추가한 뒤 clasp push 하면 적용됩니다.

function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('QR/NFC 출결관리')
    .addItem('관리 메뉴 화면 열기', 'showQrAttendanceMenu')
    .addSeparator()
    .addItem('1. 전체 갱신', 'menuRefreshAllAttendance')
    .addItem('2. 출결결과 갱신', 'menuSyncAttendance')
    .addItem('3. 담임허락체크 갱신', 'menuUpdateTeacherApprovalCheck')
    .addItem('4. 야자참여분석 갱신', 'menuRefreshYajaParticipationAnalysis')
    .addItem('5. 웹앱응답 중복 정리', 'menuDedupeWebAppResponse')
    .addSeparator()
    .addSubMenu(
      SpreadsheetApp.getUi()
        .createMenu('웹앱 주소')
        .addItem('웹앱 주소 탭 만들기/갱신', 'menuCreateWebAppLinksSheet')
        .addItem('웹앱 주소 팝업 보기', 'menuShowWebAppLinksDialog')
    )
    .addSubMenu(
      SpreadsheetApp.getUi()
        .createMenu('NFC/앱 점검')
        .addItem('NFC 학생명단 연동 점검', 'menuCheckNfcRoster')
        .addItem('오늘 출석상태 API 점검', 'menuCheckTodayAttendanceStatus')
    )
    .addSubMenu(
      SpreadsheetApp.getUi()
        .createMenu('자동 실행 트리거')
        .addItem('출결 자동갱신 트리거 설치/갱신', 'menuInstallScheduledAttendanceTrigger')
        .addItem('출결 자동갱신 트리거 삭제', 'menuDeleteScheduledAttendanceTriggers')
    )
    .addSubMenu(
      SpreadsheetApp.getUi()
        .createMenu('탭 정리')
        .addItem('주요 탭 순서 정리', 'menuOrganizeQrSheets')
        .addItem('원본/보조 탭 숨기기', 'menuHideHelperQrSheets')
        .addItem('숨김 탭 모두 보이기', 'menuShowAllQrSheets')
    )
    .addToUi();
}

function onInstall(e) {
  onOpen(e);
}

function showQrAttendanceMenu() {
  const html = HtmlService.createHtmlOutputFromFile('QR_MenuSidebar')
    .setTitle('QR/NFC 출결관리')
    .setWidth(360);
  SpreadsheetApp.getUi().showSidebar(html);
}

function menuRefreshAllAttendance() {
  return qrMenuRun_('전체 갱신', function () {
    syncAttendance();
    refreshYajaParticipationAnalysis();
    return '출결결과, 담임허락체크, 야자참여분석을 갱신했습니다.';
  });
}

function menuSyncAttendance() {
  return qrMenuRun_('출결결과 갱신', function () {
    syncAttendance();
    return '출결결과를 갱신했습니다. 담임허락체크도 함께 갱신됩니다.';
  });
}

function menuUpdateTeacherApprovalCheck() {
  return qrMenuRun_('담임허락체크 갱신', function () {
    updateTeacherApprovalCheck();
    return '담임허락체크를 갱신했습니다.';
  });
}

function menuRefreshYajaParticipationAnalysis() {
  return qrMenuRun_('야자참여분석 갱신', function () {
    refreshYajaParticipationAnalysis();
    return '야자참여분석을 갱신했습니다.';
  });
}

function menuDedupeWebAppResponse() {
  return qrMenuRun_('웹앱응답 중복 정리', function () {
    var result = dedupeWebAppResponseSheet_();
    syncAttendance(); // 정리한 로그를 바로 출결결과/담임허락체크에도 반영
    if (result.removed === 0) {
      return '웹앱응답에 중복 기록이 없었습니다.';
    }
    return '웹앱응답에서 중복 기록 ' + result.removed + '건을 삭제했습니다. 출결결과/담임허락체크도 함께 갱신했습니다.';
  }, true);
}

function menuCheckNfcRoster() {
  return qrMenuRun_('NFC 학생명단 연동 점검', function () {
    const result = getWeeklyRosterFullData();
    if (!result || result.result !== 'success') {
      throw new Error(result && result.message ? result.message : 'NFC 학생명단 연동 실패');
    }
    const count = result.data ? result.data.length : 0;
    return 'NFC 학생명단 연동 정상: ' + count + '건, 모드: ' + (result.mode || '확인불가');
  }, true);
}

function menuCheckTodayAttendanceStatus() {
  return qrMenuRun_('오늘 출석상태 API 점검', function () {
    const result = getTodayAttendanceStatus();
    if (!result || result.result !== 'success') {
      throw new Error(result && result.message ? result.message : '오늘 출석상태 API 점검 실패');
    }
    const count = result.data ? result.data.length : 0;
    return '오늘 출석상태 API 정상: ' + count + '건';
  }, true);
}

function menuCreateWebAppLinksSheet() {
  return qrMenuRun_('웹앱 주소 탭 갱신', function () {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const sheetName = '00_웹앱주소';
    let sheet = ss.getSheetByName(sheetName);
    if (!sheet) sheet = ss.insertSheet(sheetName, 0);
    sheet.clear();

    const urls = qrMenuGetWebAppUrls_();
    const rows = [
      ['항목', '주소', '용도'],
      ['학생 출석 화면', urls.student, '학생 QR/웹앱 출석 제출용'],
      ['담임 출결 확인', urls.teacher, '담임교사용 결석 확인 및 사유 처리'],
      ['담임 조회 화면', urls.view, '담임교사용 조회 화면'],
      ['학년부장 현황', urls.grade, '학년부장/관리자 전체 현황'],
      ['NFC 앱 GET_MASTER', urls.getMaster, '안드로이드 앱 학생명단 동기화 API'],
      ['NFC 앱 GET_TODAY_STATUS', urls.getTodayStatus, '안드로이드 앱 오늘 출석 상태 확인 API'],
      ['갱신일시', new Date(), '']
    ];
    sheet.getRange(1, 1, rows.length, rows[0].length).setValues(rows);
    sheet.getRange(1, 1, 1, 3).setFontWeight('bold').setBackground('#d9ead3');
    sheet.setColumnWidth(1, 180);
    sheet.setColumnWidth(2, 720);
    sheet.setColumnWidth(3, 320);
    sheet.getRange(1, 1, rows.length, 3).setWrap(true).setVerticalAlignment('middle');
    sheet.setFrozenRows(1);
    ss.setActiveSheet(sheet);
    return '00_웹앱주소 탭을 만들거나 갱신했습니다.';
  });
}

function menuShowWebAppLinksDialog() {
  const urls = qrMenuGetWebAppUrls_();
  const html = HtmlService.createHtmlOutput(
    '<div style="font-family:Arial,sans-serif;padding:16px;line-height:1.6">' +
      '<h2 style="margin-top:0">QR/NFC 출결 웹앱 주소</h2>' +
      qrMenuLinkHtml_('학생 출석 화면', urls.student) +
      qrMenuLinkHtml_('담임 출결 확인', urls.teacher) +
      qrMenuLinkHtml_('담임 조회 화면', urls.view) +
      qrMenuLinkHtml_('학년부장 현황', urls.grade) +
      '<p style="margin-top:16px;color:#666">주소가 비어 있으면 웹앱 배포가 아직 완료되지 않은 상태입니다.</p>' +
    '</div>'
  ).setWidth(520).setHeight(360);
  SpreadsheetApp.getUi().showModalDialog(html, '웹앱 주소');
}

function menuInstallScheduledAttendanceTrigger() {
  return qrMenuRun_('출결 자동갱신 트리거 설치', function () {
    qrMenuDeleteTriggersByHandler_('runScheduledAttendance');
    ScriptApp.newTrigger('runScheduledAttendance')
      .timeBased()
      .everyMinutes(5)
      .create();
    return 'runScheduledAttendance 5분 간격 자동 실행 트리거를 설치했습니다.';
  }, true);
}

function menuDeleteScheduledAttendanceTriggers() {
  return qrMenuRun_('출결 자동갱신 트리거 삭제', function () {
    const count = qrMenuDeleteTriggersByHandler_('runScheduledAttendance');
    return 'runScheduledAttendance 자동 실행 트리거 ' + count + '개를 삭제했습니다.';
  }, true);
}

function menuOrganizeQrSheets() {
  return qrMenuRun_('주요 탭 순서 정리', function () {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const order = [
      '00_웹앱주소',
      '출결결과',
      '담임허락체크',
      '야자참여분석',
      'NFC학생현황',
      '웹앱응답',
      '폼응답',
      '수업일정',
      'QR기준시간'
    ];

    for (let i = order.length - 1; i >= 0; i--) {
      const sheet = ss.getSheetByName(order[i]);
      if (!sheet) continue;
      sheet.showSheet();
      ss.setActiveSheet(sheet);
      ss.moveActiveSheet(1);
      qrMenuFreezeHeader_(sheet);
    }
    return '주요 탭 순서를 정리했습니다.';
  });
}

function menuHideHelperQrSheets() {
  return qrMenuRun_('원본/보조 탭 숨기기', function () {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const helperNames = [
      '폼응답',
      '웹앱응답',
      '수업일정',
      'QR기준시간'
    ];
    let hidden = 0;
    helperNames.forEach(function (name) {
      const sheet = ss.getSheetByName(name);
      if (sheet && !sheet.isSheetHidden()) {
        sheet.hideSheet();
        hidden++;
      }
    });
    return '원본/보조 탭 ' + hidden + '개를 숨겼습니다.';
  });
}

function menuShowAllQrSheets() {
  return qrMenuRun_('숨김 탭 모두 보이기', function () {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    let shown = 0;
    ss.getSheets().forEach(function (sheet) {
      if (sheet.isSheetHidden()) {
        sheet.showSheet();
        shown++;
      }
    });
    return '숨김 탭 ' + shown + '개를 다시 표시했습니다.';
  });
}

function qrMenuRun_(title, worker, showAlert) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  try {
    const message = worker();
    SpreadsheetApp.flush();
    ss.toast(message, title, 5);
    if (showAlert) SpreadsheetApp.getUi().alert(title, message, SpreadsheetApp.getUi().ButtonSet.OK);
    return { success: true, message: message };
  } catch (error) {
    const message = title + ' 실패: ' + (error && error.message ? error.message : String(error));
    ss.toast(message, title, 8);
    if (showAlert) SpreadsheetApp.getUi().alert(title, message, SpreadsheetApp.getUi().ButtonSet.OK);
    return { success: false, message: message };
  }
}

function qrMenuGetWebAppUrls_() {
  const base = ScriptApp.getService().getUrl() || '';
  const add = function (query) {
    if (!base) return '';
    return base + (base.indexOf('?') === -1 ? '?' : '&') + query;
  };
  return {
    base: base,
    student: base,
    teacher: add('page=teacher'),
    view: add('page=view'),
    grade: add('page=grade'),
    getMaster: add('mode=GET_MASTER'),
    getTodayStatus: add('mode=GET_TODAY_STATUS')
  };
}

function qrMenuLinkHtml_(label, url) {
  const safeLabel = String(label || '').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const safeUrl = String(url || '').replace(/"/g, '&quot;');
  if (!url) {
    return '<p><b>' + safeLabel + '</b><br><span style="color:#c00">웹앱 배포 URL 없음</span></p>';
  }
  return '<p><b>' + safeLabel + '</b><br><a href="' + safeUrl + '" target="_blank">' + safeUrl + '</a></p>';
}

function qrMenuDeleteTriggersByHandler_(handlerName) {
  const triggers = ScriptApp.getProjectTriggers();
  let count = 0;
  triggers.forEach(function (trigger) {
    if (trigger.getHandlerFunction && trigger.getHandlerFunction() === handlerName) {
      ScriptApp.deleteTrigger(trigger);
      count++;
    }
  });
  return count;
}

function qrMenuFreezeHeader_(sheet) {
  if (!sheet) return;
  try {
    if (sheet.getLastRow() >= 1) sheet.setFrozenRows(1);
    if (sheet.getLastColumn() > 0) sheet.autoResizeColumns(1, Math.min(sheet.getLastColumn(), 12));
  } catch (e) {
    // 서식 정리 실패는 핵심 기능에 영향이 없으므로 무시합니다.
  }
}

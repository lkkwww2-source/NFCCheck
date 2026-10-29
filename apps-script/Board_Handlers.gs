// [Board_Handlers.gs] 교실 전자칠판(터치스크린) 실시간 출결판
// Board.html이 google.script.run으로 직접 호출하는 서버 함수 모음.
//
// 전자칠판에서의 출석/지각 토글도 안드로이드 앱의 수동 체크와 동일한 성격이므로,
// 같은 원칙으로 해당 타임의 "시작시각"으로 고정 기록한다 (늦게 눌러도 정시 참여로 남도록).
// ⚠️ 아래 시작시각 표는 android/.../MainActivity.kt 의 PERIODS 표와 값이 같아야 한다.
//    운영 시간대를 바꿀 때는 반드시 두 곳을 함께 수정할 것.
var BOARD_PERIOD_START_TIME = {
  '1타임': '17:00:00',
  '2타임': '19:00:00',
  '3타임': '20:20:00'
};

function inferGradeFromStudentId_(studentId) {
  var id = String(studentId || '').trim();
  return id.length > 0 ? id.charAt(0) : '';
}

function buildTodayRecordRows_() {
  var studentMap = {};
  getStudentMasterList_().forEach(function (s) { studentMap[s.studentId] = s; });

  var todayText = normalizeDateText(new Date());
  var attendanceResult = getAttendanceByDate(todayText);
  var rawRecords = (attendanceResult && attendanceResult.result === 'success') ? attendanceResult.data : [];

  // board.html 형식: [기록시각, '오늘', 타임, 학번, 이름, 상태]
  return rawRecords.map(function (r) {
    var info = studentMap[r.studentNum] || {};
    return [r.recordTime, '오늘', r.slotName, r.studentNum, info.name || '', r.status];
  });
}

// 전자칠판 최초 로딩: 학생 명부 + 오늘 출결 기록을 한 번에 내려준다.
function getInitialData() {
  var students = getStudentMasterList_().map(function (s) {
    var classNum = s.className.replace('반', '');
    // board.html 형식: [학년, 반, 학번, 이름]
    return [inferGradeFromStudentId_(s.studentId), classNum, s.studentId, s.name];
  });

  return { students: students, records: buildTodayRecordRows_() };
}

// 전자칠판 주기적 갱신용: 안드로이드 앱/NFC로 들어온 최신 기록을 반영하기 위해
// 교사가 칠판을 직접 터치하지 않아도 주기적으로 다시 불러온다.
function getTodayRecords() {
  return buildTodayRecordRows_();
}

// 전자칠판에서 출석/지각을 토글할 때 호출됨.
// updates: [{ id, name, slot, status, timestamp }], status: '출석' | '지각' | '미확인'(=기록 삭제)
function syncBatch(updates) {
  var studentMap = {};
  getStudentMasterList_().forEach(function (s) { studentMap[s.studentId] = s; });

  var todayText = normalizeDateText(new Date());
  var records = (updates || []).map(function (u) {
    var studentId = cleanStudentId(u.id);
    var info = studentMap[studentId] || {};
    var isReset = u.status === '미확인';
    return {
      studentNum: studentId,
      name: info.name || u.name || '',
      className: info.className || '',
      slotName: u.slot,
      status: isReset ? '리셋' : u.status,
      date: todayText,
      recordTime: BOARD_PERIOD_START_TIME[u.slot] || todayText,
      inputType: '전자칠판'
    };
  });

  return saveAndroidAttendance(records);
}

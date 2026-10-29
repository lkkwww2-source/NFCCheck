// [Web_Student.gs] 학생용 QR 자가출석 웹페이지(Index.html) 백엔드
// 사전 신청 여부를 검사하지 않는다 — NFC학생현황에 등록된 학번이면 누구나 지금 바로 출석 처리된다.
//
// 타임 판정은 안드로이드 앱(MainActivity.kt의 PERIODS)과 동일한 시간대를 쓴다.
// ⚠️ 운영 시간대를 바꿀 때는 반드시 MainActivity.kt의 PERIODS도 함께 수정할 것.
var WEB_PERIODS_ = [
  { slotName: '1타임', onTimeStart: 17 * 60, onTimeEnd: 17 * 60 + 14, lateEnd: 18 * 60 + 30 },
  { slotName: '2타임', onTimeStart: 19 * 60, onTimeEnd: 19 * 60 + 14, lateEnd: 20 * 60 + 10 },
  { slotName: '3타임', onTimeStart: 20 * 60 + 20, onTimeEnd: 20 * 60 + 34, lateEnd: 21 * 60 + 30 }
];

function resolveWebPeriod_(now) {
  var totalMin = now.getHours() * 60 + now.getMinutes();
  for (var i = 0; i < WEB_PERIODS_.length; i++) {
    var p = WEB_PERIODS_[i];
    if (totalMin >= p.onTimeStart && totalMin <= p.onTimeEnd) return { slotName: p.slotName, status: '출석' };
    if (totalMin > p.onTimeEnd && totalMin <= p.lateEnd) return { slotName: p.slotName, status: '지각' };
  }
  return null;
}

// 학생용 QR 자가출석 제출.
// ⚠️ 예전 버전의 버그 재발 방지: 저장은 항상 "지금 실제 시각"을 그대로 쓰고(date/recordTime을
// saveAndroidAttendance에 명시적으로 전달), 서버가 나중에 타임스탬프만 보고 타임을 재계산하는 일이
// 없도록 한다 (saveAndroidAttendance/getAttendanceByDate는 저장된 타임 값을 그대로 신뢰한다).
function submitWebAttendance(studentId) {
  try {
    var cleanId = cleanStudentId(studentId);
    if (!cleanId) return { success: false, message: '학번을 입력하세요.' };

    var master = {};
    getStudentMasterList_().forEach(function (s) { master[s.studentId] = s; });
    var student = master[cleanId];
    if (!student) return { success: false, message: '등록되지 않은 학번입니다. 선생님께 문의하세요.' };

    var now = new Date();
    var resolved = resolveWebPeriod_(now);
    if (!resolved) {
      return { success: false, message: '지금은 출석 가능 시간이 아닙니다.' };
    }

    var tz = Session.getScriptTimeZone();
    var dateText = Utilities.formatDate(now, tz, 'yyyy-MM-dd');
    var timeText = Utilities.formatDate(now, tz, 'HH:mm:ss');

    var saveResult = saveAndroidAttendance([{
      studentNum: cleanId,
      status: resolved.status,
      slotName: resolved.slotName,
      date: dateText,
      recordTime: timeText,
      inputType: 'QR'
    }]);

    if (saveResult.result !== 'success') {
      return { success: false, message: '저장 중 오류가 발생했습니다: ' + (saveResult.message || '') };
    }

    return {
      success: true,
      message: resolved.status + ' 처리되었습니다.',
      studentId: cleanId,
      name: student.name,
      className: student.className,
      slotName: resolved.slotName,
      status: resolved.status,
      time: Utilities.formatDate(now, tz, 'yyyy. M. d HH:mm:ss')
    };
  } catch (error) {
    return { success: false, message: '오류: ' + error.toString() };
  }
}

function checkTeacherPassword(pw) {
  return pw === 'hsm2026';
}

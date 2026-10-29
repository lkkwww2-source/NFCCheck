// [Teacher_Handlers.gs] 교사용 웹 대시보드(Teacher.html, Grade.html) 백엔드
// 신청자 명단이 없으므로 "결석"은 자동으로 판정되지 않는다 — 담임이 화면에서 직접 지정한다.

// 날짜 + 반 기준, 타임별 학생 출결 현황
function getTeacherDailySummary(dateText, className) {
  try {
    var target = normalizeDateText(dateText);
    var students = getStudentMasterList_().filter(function (s) { return s.className === className; });

    var recordsResult = getAttendanceByDate(target);
    var recordList = (recordsResult.result === 'success') ? recordsResult.data : [];
    var recordMap = {};
    recordList.forEach(function (r) { recordMap[r.studentNum + '|' + r.slotName] = r; });

    var slots = ['1타임', '2타임', '3타임'];
    return slots.map(function (slotName) {
      var items = students.map(function (s) {
        var rec = recordMap[s.studentId + '|' + slotName];
        return {
          studentId: s.studentId,
          name: s.name,
          className: s.className,
          status: rec ? rec.status : '미기록',
          recordTime: rec ? rec.recordTime : '',
          inputType: rec ? rec.inputType : '',
          reason: rec ? rec.reason : ''
        };
      });
      return { slotName: slotName, students: items };
    });
  } catch (error) {
    return { error: error.toString() };
  }
}

// 담임이 직접 결석 처리 (자동 판정 없음, 사유는 선택 입력)
function markAbsentFromTeacherWeb(studentId, dateText, slotName, reason) {
  try {
    var cleanId = cleanStudentId(studentId);
    var master = {};
    getStudentMasterList_().forEach(function (s) { master[s.studentId] = s; });
    if (!master[cleanId]) return { result: 'fail', message: '학생을 찾을 수 없습니다.' };

    var target = normalizeDateText(dateText);
    return saveAndroidAttendance([{
      studentNum: cleanId,
      status: '결석',
      slotName: slotName,
      date: target,
      recordTime: '00:00:00',
      inputType: '담임웹',
      reason: reason || ''
    }]);
  } catch (error) {
    return { result: 'fail', message: error.toString() };
  }
}

// 담임이 잘못 처리한 기록을 취소(미기록으로 되돌림)
function resetAttendanceFromTeacherWeb(studentId, dateText, slotName) {
  try {
    var target = normalizeDateText(dateText);
    return saveAndroidAttendance([{
      studentNum: cleanStudentId(studentId),
      status: '리셋',
      slotName: slotName,
      date: target,
      recordTime: '00:00:00',
      inputType: '담임웹'
    }]);
  } catch (error) {
    return { result: 'fail', message: error.toString() };
  }
}

// 학년부장용 - 날짜 기준 전체 반 현황
function getGradeDailySummary(dateText) {
  try {
    var target = normalizeDateText(dateText);
    var students = getStudentMasterList_();

    var recordsResult = getAttendanceByDate(target);
    var recordList = (recordsResult.result === 'success') ? recordsResult.data : [];
    var recordsByStudent = {};
    recordList.forEach(function (r) {
      if (!recordsByStudent[r.studentNum]) recordsByStudent[r.studentNum] = {};
      recordsByStudent[r.studentNum][r.slotName] = r;
    });

    var classes = {};
    students.forEach(function (s) {
      if (!classes[s.className]) classes[s.className] = [];
      var rec = recordsByStudent[s.studentId] || {};
      var get = function (slot) { return (rec[slot] && rec[slot].status) || '미기록'; };
      classes[s.className].push({
        studentId: s.studentId,
        name: s.name,
        period1: get('1타임'),
        period2: get('2타임'),
        period3: get('3타임')
      });
    });

    var classNames = Object.keys(classes).sort(function (a, b) {
      return (a.replace('반', '').padStart(2, '0')).localeCompare(b.replace('반', '').padStart(2, '0'));
    });

    return {
      date: target,
      classNames: classNames,
      classes: classes
    };
  } catch (error) {
    return { error: error.toString() };
  }
}

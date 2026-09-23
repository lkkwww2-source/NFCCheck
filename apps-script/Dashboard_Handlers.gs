// [Dashboard_Handlers.gs] 안드로이드 '누적현황(STATS)' 탭용 월별 통계
// 담임 승인/사유 워크플로우가 사라졌으므로 '웹앱응답' 로그를 직접 집계한다.
// (웹앱응답은 학번만 들고 있고 이름/반은 없으므로 NFC학생현황과 조인해서 채운다)

function getMonthlyStatsForAndroid(month) {
  try {
    var sheet = getAttendanceSheet_();
    if (sheet.getLastRow() < 2) {
      return { month: month, slots: [], students: [], top10: [], bottom10: [] };
    }

    var masterMap = {};
    getStudentMasterList_().forEach(function (s) { masterMap[s.studentId] = s; });

    var data = sheet.getDataRange().getValues();
    var slotSet = {};
    var studentMap = {};

    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      var dateRaw = row[5]; // 실제날짜
      if (!dateRaw) continue;
      var dateKey = normalizeDateText(dateRaw);
      var monthText = dateKey.substring(0, 7);
      if (monthText !== month) continue;

      var slotName = String(row[4] || '').trim();
      var studentNum = cleanStudentId(row[1]);
      var status = decodeStatusFromInputMethod_(row[6]);
      if (!studentNum || !slotName || !status) continue;

      var info = masterMap[studentNum] || {};
      var name = info.name || '';
      // 여러 학년이 섞일 수 있어 "3반"만으로는 어느 학년인지 알 수 없으므로
      // 학번 첫 자리(학년)를 붙여 "1-3반" 형태로 만든다. (studentGrade()/inferGradeFromStudentId_ 와 동일한 규칙)
      var rawClassName = info.className || '';
      var className = studentNum.charAt(0) + '-' + rawClassName;

      var slotKey = dateKey + '|' + slotName;
      slotSet[slotKey] = true;

      if (!studentMap[studentNum]) {
        studentMap[studentNum] = {
          studentNum: studentNum, name: name, className: className,
          present: 0, late: 0, absent: 0, total: 0, slotMap: {}
        };
      }
      var student = studentMap[studentNum];
      student.total++;
      if (status === '출석' || status === '학사') {
        student.present++;
        student.slotMap[slotKey] = status;
      } else if (status === '지각') {
        student.late++;
        student.slotMap[slotKey] = '지각';
      } else if (status === '결석') {
        student.absent++;
        student.slotMap[slotKey] = '결석';
      } else {
        student.slotMap[slotKey] = status;
      }
    }

    var slots = Object.keys(slotSet).sort();

    var students = Object.keys(studentMap).map(function (key) {
      var s = studentMap[key];
      return {
        studentNum: s.studentNum, name: s.name, className: s.className,
        present: s.present, late: s.late, absent: s.absent, total: s.total,
        slots: slots.map(function (slotKey) { return s.slotMap[slotKey] || ''; })
      };
    });

    students.sort(function (a, b) {
      if (String(a.className) !== String(b.className)) return String(a.className).localeCompare(String(b.className), 'ko');
      return String(a.studentNum).localeCompare(String(b.studentNum), 'ko');
    });

    var rankBase = students.map(function (s) {
      var score = (s.present * 1.0) + (s.late * 0.5) - (s.absent * 1.0);
      return {
        studentNum: s.studentNum, name: s.name, className: s.className,
        present: s.present, late: s.late, absent: s.absent, total: s.total,
        slots: s.slots, score: score
      };
    });

    var top10 = rankBase.slice().sort(function (a, b) {
      if (b.score !== a.score) return b.score - a.score;
      if (b.present !== a.present) return b.present - a.present;
      return a.absent - b.absent;
    }).slice(0, 10).map(function (x) { delete x.score; return x; });

    var bottom10 = rankBase.slice().sort(function (a, b) {
      if (a.score !== b.score) return a.score - b.score;
      if (b.absent !== a.absent) return b.absent - a.absent;
      return a.present - b.present;
    }).slice(0, 10).map(function (x) { delete x.score; return x; });

    return { month: month, slots: slots, students: students, top10: top10, bottom10: bottom10 };
  } catch (error) {
    return { error: String(error) };
  }
}

// [Dashboard_Handlers.gs] 대시보드 연동 및 안드로이드 원격 결재 핸들러
// ─────────────────────────────────────────────
// 담임 승인/사유 워크플로우(Teacher.html/View.html/Grade.html, 담임허락체크 시트)는 기존과
// 동일하게 유지한다. 출결결과는 이제 참여명단 없이 채워지지만, 참여구분은 항상 '자율학습'로
// 고정되어 들어오므로 아래 필터(type === '자율학습' || '향상반')는 그대로 동작한다.
// ─────────────────────────────────────────────

function getTeacherDailySummary(dateText, className) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const resultSheet = ss.getSheetByName('출결결과'); const approvalSheet = getApprovalSheet();
  const resultData = resultSheet.getDataRange().getValues(); const approvalData = approvalSheet.getDataRange().getValues();
  const targetDate = normalizeDateText(dateText); const memoMap = getStudentMemoMap(dateText);
  const periods = ['1타임', '2타임', '3타임'];
  const approvedMap = {};
  for (let i = 1; i < approvalData.length; i++) {
    const row = approvalData[i];
    const key = [normalizeDateText(row[0]), String(row[4] || '').trim(), String(row[5] || '').trim()].join('|');
    approvedMap[key] = { approved: String(row[8] || 'N').trim() === 'Y', reason: row[9] || '' };
  }
  const summary = {}; periods.forEach(p => { summary[p] = { period: p, applicantCount: 0, presentCount: 0, memoCount: 0, absenceCount: 0, presentStudents: [], memoStudents: [], absentStudents: [] }; });
  for (let i = 1; i < resultData.length; i++) {
    const row = resultData[i]; const date = normalizeDateText(row[0]); const day = row[2]; const period = String(row[3] || '').trim(); const studentId = String(row[4] || '').trim(); const name = String(row[5] || '').trim(); const cls = String(row[6] || '').trim(); const type = String(row[8] || '').trim(); const attendance = String(row[10] || '').trim();
    if (date !== targetDate) continue; if (cls !== className) continue; if (!summary[period]) continue; if (!(type === '자율학습' || type === '향상반')) continue;
    const key = `${date}|${period}|${studentId}`; const approved = approvedMap[key]; const memo = memoMap[key] || '';
    summary[period].applicantCount++;
    const studentObj = { date, day, period, className: cls, studentId, name, type, approved: approved ? approved.approved : false, reason: approved ? approved.reason : '', memo };
    if (attendance === '출석') { summary[period].presentCount++; summary[period].presentStudents.push(studentObj); }
    else if (attendance === '결석') {
      if (memo) { summary[period].memoCount++; summary[period].memoStudents.push(studentObj); }
      else { summary[period].absenceCount++; summary[period].absentStudents.push(studentObj); }
    }
  }
  return periods.map(p => summary[p]);
}

function saveTeacherApprovals(items) {
  const sheet = getApprovalSheet(); const data = sheet.getDataRange().getValues(); const existingMap = {};
  for (let i = 1; i < data.length; i++) { existingMap[[normalizeDateText(data[i][0]), String(data[i][4]).trim(), String(data[i][5]).trim()].join('|')] = i + 1; }
  items.forEach(item => {
    const rowIndex = existingMap[[normalizeDateText(item.date), item.period, item.studentId].join('|')]; if (!rowIndex) return;
    sheet.getRange(rowIndex, 9).setValue(item.approved ? 'Y' : 'N'); sheet.getRange(rowIndex, 10).setValue(item.reason || ''); sheet.getRange(rowIndex, 11).setValue(new Date());
  });
  return '저장 완료';
}

function getApprovalSheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet(); let sheet = ss.getSheetByName('담임허락체크');
  if (!sheet) { sheet = ss.insertSheet('담임허락체크'); }
  const header = ['날짜', '신청월', '반', '요일', '타임', '학번', '이름', '참여구분', '담임허락', '사유', '확인시간'];
  sheet.getRange(1, 1, 1, header.length).setValues([header]); return sheet;
}

function getGradeDailySummary(dateText) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const resultSheet = ss.getSheetByName('출결결과'); const approvalSheet = getApprovalSheet();
  const resultData = resultSheet.getDataRange().getValues(); const approvalData = approvalSheet.getDataRange().getValues();
  const targetDate = normalizeDateText(dateText); const periods = ['1타임', '2타임', '3타임']; const classes = ['1반', '2반', '3반', '4반', '5반', '6반', '7반', '8반', '9반'];
  const approvedMap = {};
  for (let i = 1; i < approvalData.length; i++) {
    const row = approvalData[i]; const key = [normalizeDateText(row[0]), String(row[4]).trim(), String(row[5]).trim()].join('|');
    approvedMap[key] = { approved: String(row[8] || 'N').trim() === 'Y', reason: row[9] || '' };
  }
  const summary = { date: targetDate, totalApplicant: 0, totalAbsent: 0, totalApproved: 0, totalUnconfirmed: 0, periods: {}, classes: {} };
  periods.forEach(p => { summary.periods[p] = { period: p, applicantCount: 0, absenceCount: 0, approvedCount: 0, unconfirmedCount: 0 }; });
  classes.forEach(cls => { summary.classes[cls] = { className: cls, applicantCount: 0, absenceCount: 0, approvedCount: 0, unconfirmedCount: 0, absences: [] }; });
  for (let i = 1; i < resultData.length; i++) {
    const row = resultData[i]; const date = normalizeDateText(row[0]); const period = String(row[3] || '').trim(); const studentId = String(row[4] || '').trim(); const name = String(row[5] || '').trim(); const cls = String(row[6] || '').trim(); const type = String(row[8] || '').trim(); const attendance = String(row[10] || '').trim();
    if (date !== targetDate) continue; if (!summary.periods[period]) continue; if (!summary.classes[cls]) continue;
    if (type === '자율학습' || type === '향상반') { summary.totalApplicant++; summary.periods[period].applicantCount++; summary.classes[cls].applicantCount++; }
    if (attendance === '결석') {
      const key = [date, period, studentId].join('|'); const approved = approvedMap[key]; const isApproved = approved && approved.approved;
      summary.totalAbsent++; summary.periods[period].absenceCount++; summary.classes[cls].absenceCount++;
      if (isApproved) { summary.totalApproved++; summary.periods[period].approvedCount++; summary.classes[cls].approvedCount++; }
      else { summary.totalUnconfirmed++; summary.periods[period].unconfirmedCount++; summary.classes[cls].unconfirmedCount++; }
      summary.classes[cls].absences.push({ period, studentId, name, type, approved: !!isApproved, reason: approved ? approved.reason : '' });
    }
  }
  return summary;
}

function getStudentMemoMap(dateText) {
  const ss = SpreadsheetApp.getActiveSpreadsheet(); const sheet = ss.getSheetByName('웹앱응답'); const map = {};
  if (!sheet || sheet.getLastRow() < 2) return map;
  const data = sheet.getDataRange().getValues(); const targetDate = normalizeDateText(dateText);
  for (let i = 1; i < data.length; i++) {
    const row = data[i]; const studentId = String(row[1] || '').trim(); const period = String(row[4] || '').trim(); const realDate = normalizeDateText(row[5]); const inputType = String(row[6] || '').trim(); const memo = String(row[7] || '').trim();
    if (!studentId || !period || !memo) continue; if (realDate !== targetDate) continue; if (inputType !== '웹앱사유') continue;
    map[`${realDate}|${period}|${studentId}`] = memo;
  }
  return map;
}

// 🌟 [안드로이드 전용 수신 API 1] 담임교사용 조회 단추 처리 연산
function getAndroidAttendanceLogs(dateText, className) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet(); var sheet = ss.getSheetByName('담임허락체크');
    if (!sheet) return { "result": "success", "data": [] };
    var values = sheet.getDataRange().getValues(); var targetDate = normalizeDateText(dateText); var filteredList = [];
    for (var i = 1; i < values.length; i++) {
      var row = values[i]; var rowClass = String(row[2] || "").trim();
      var compClass = className.indexOf("반") === -1 ? className + "반" : className;
      if (rowClass.indexOf("반") === -1) rowClass += "반";
      if (normalizeDateText(row[0]) === targetDate && rowClass === compClass) {
        var pName = String(row[4] || "").trim();
        var mappedSlot = pName === "2타임" ? "야간1" : (pName === "3타임" ? "야간2" : "방과후");
        filteredList.push({
          "date": targetDate, "studentNum": cleanStudentId(row[5]), "name": String(row[6] || "").trim(),
          "className": rowClass, "slotName": mappedSlot, "status": "결석", "reason": String(row[9] || "").trim()
        });
      }
    }
    return { "result": "success", "data": filteredList };
  } catch (error) { return { "result": "fail", "message": error.toString() }; }
}

// 🌟 [안드로이드 전용 수신 API 2] 스마트폰 사유 등록 시 시트 즉시 기입 연산
function updateAndroidAbsenceReason(params) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet(); var sheet = ss.getSheetByName('담임허락체크');
    var values = sheet.getDataRange().getValues(); var targetDate = normalizeDateText(params.date);
    var targetSlot = params.slotName === "야간1" ? "2타임" : (params.slotName === "야간2" ? "3타임" : "1타임");
    for (var i = 1; i < values.length; i++) {
      if (normalizeDateText(values[i][0]) === targetDate && String(values[i][4]).trim() === targetSlot && cleanStudentId(values[i][5]) === cleanStudentId(params.studentNum)) {
        sheet.getRange(i + 1, 9).setValue('Y'); sheet.getRange(i + 1, 10).setValue(params.reason); sheet.getRange(i + 1, 11).setValue(new Date());
        return { "result": "success", "message": "성공" };
      }
    }
    return { "result": "fail", "message": "검색 실패" };
  } catch (error) { return { "result": "fail", "message": error.toString() }; }
}

// ✅ 안드로이드 전용 API 4: 누적현황 탭용 월별 통계
function getMonthlyStatsForAndroid(month) {
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var resultSheet = ss.getSheetByName('출결결과');
    var approvalSheet = ss.getSheetByName('담임허락체크');

    if (!resultSheet) {
      return { error: '출결결과 시트를 찾을 수 없습니다.' };
    }

    var resultValues = resultSheet.getDataRange().getValues();
    var approvalMap = {};

    if (approvalSheet && approvalSheet.getLastRow() >= 2) {
      var approvalValues = approvalSheet.getDataRange().getValues();
      for (var i = 1; i < approvalValues.length; i++) {
        var a = approvalValues[i];
        var approved = String(a[8] || '').trim();
        if (approved !== 'Y') continue;

        var dateKey = formatDateKey(a[0]);
        var period = String(a[4] || '').trim();
        var studentId = cleanStudentId(a[5]);
        approvalMap[dateKey + '|' + period + '|' + studentId] = true;
      }
    }

    var slotSet = {};
    var studentMap = {};

    for (var r = 1; r < resultValues.length; r++) {
      var row = resultValues[r];
      var dateRaw = row[0];
      var monthText = normalizeMonth(row[1]);
      var period = String(row[3] || '').trim();
      var studentNum = cleanStudentId(row[4]);
      var name = String(row[5] || '').trim();
      var className = String(row[6] || '').trim();
      var type = String(row[8] || '').trim();
      var attendance = String(row[10] || '').trim();
      var entryStatus = String(row[11] || '').trim();

      if (!dateRaw || !studentNum || monthText !== month) continue;
      if (!(type === '자율학습' || type === '향상반')) continue;

      var dateKey = formatDateKey(dateRaw);
      var slotName = period === '1타임' ? '방과후' : (period === '2타임' ? '야간1' : '야간2');
      var slotKey = dateKey + '|' + slotName;
      slotSet[slotKey] = true;

      if (!studentMap[studentNum]) {
        studentMap[studentNum] = {
          studentNum: studentNum,
          name: name,
          className: className,
          present: 0,
          late: 0,
          absent: 0,
          total: 0,
          slotMap: {}
        };
      }

      var student = studentMap[studentNum];
      var approvalKey = dateKey + '|' + period + '|' + studentNum;
      var finalStatus = attendance;

      if (attendance === '결석' && approvalMap[approvalKey]) {
        finalStatus = '인정';
      }

      student.total++;
      if (attendance === '출석' && entryStatus === '지각') {
      student.late++;
      } else if (attendance === '출석') {
      student.present++;
      } else if (attendance === '결석' && !approvalMap[approvalKey]) {
        student.absent++;
      }

      if (attendance === '출석' && entryStatus === '지각') {
        student.slotMap[slotKey] = '지각';
      } else if (attendance === '출석') {
        student.slotMap[slotKey] = '출석';
      } else if (attendance === '결석' && approvalMap[approvalKey]) {
        student.slotMap[slotKey] = '인정';
      } else if (attendance === '결석') {
        student.slotMap[slotKey] = '결석';
      } else {
        student.slotMap[slotKey] = finalStatus || '';
      }
    }

    var slots = Object.keys(slotSet).sort();

    var students = Object.keys(studentMap).map(function(key) {
      var s = studentMap[key];
      return {
        studentNum: s.studentNum,
        name: s.name,
        className: s.className,
        present: s.present,
        late: s.late,
        absent: s.absent,
        total: s.total,
        slots: slots.map(function(slotKey) {
          return s.slotMap[slotKey] || '';
        })
      };
    });

    students.sort(function(a, b) {
      if (String(a.className) !== String(b.className)) {
        return String(a.className).localeCompare(String(b.className), 'ko');
      }
      return String(a.studentNum).localeCompare(String(b.studentNum), 'ko');
    });

    var rankBase = students.map(function(s) {
      var score = (s.present * 1.0) + (s.late * 0.5) - (s.absent * 1.0);
      return {
        studentNum: s.studentNum,
        name: s.name,
        className: s.className,
        present: s.present,
        late: s.late,
        absent: s.absent,
        total: s.total,
        slots: s.slots,
        score: score
      };
    });

    var top10 = rankBase.slice().sort(function(a, b) {
      if (b.score !== a.score) return b.score - a.score;
      if (b.present !== a.present) return b.present - a.present;
      return a.absent - b.absent;
    }).slice(0, 10).map(function(x) {
      delete x.score;
      return x;
    });

    var bottom10 = rankBase.slice().sort(function(a, b) {
      if (a.score !== b.score) return a.score - b.score;
      if (b.absent !== a.absent) return b.absent - a.absent;
      return a.present - b.present;
    }).slice(0, 10).map(function(x) {
      delete x.score;
      return x;
    });

    return {
      month: month,
      slots: slots,
      students: students,
      top10: top10,
      bottom10: bottom10
    };
  } catch (error) {
    return { error: String(error) };
  }
}

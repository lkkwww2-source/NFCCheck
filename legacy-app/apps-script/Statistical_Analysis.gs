// [Statistical_Analysis.gs] 야자 참여 통계 분석 엔진

function refreshYajaParticipationAnalysis() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const formSheet = ss.getSheetByName('폼응답'); const webSheet = ss.getSheetByName('웹앱응답');
  const resultSheet = ss.getSheetByName('출결결과'); const approvalSheet = ss.getSheetByName('담임허락체크');
  let sheet = ss.getSheetByName('야자참여분석'); if (!sheet) { sheet = ss.insertSheet('야자참여분석'); }

  const studentMap = buildStudentInfoMap_(); const recordMap = {};
  if (formSheet && formSheet.getLastRow() >= 2) {
    const data = formSheet.getDataRange().getValues();
    for (let i = 1; i < data.length; i++) {
      const row = data[i]; const studentId = String(row[1] || '').trim(); const period = String(row[4] || '').trim(); const dateRaw = row[5];
      if (!studentId || !period || !dateRaw) continue;
      const dateKey = formatDateKey(dateRaw); recordMap[`${dateKey}|${period}|${studentId}`] = { studentId, dateKey, period, type: '출석' };
    }
  }
  if (webSheet && webSheet.getLastRow() >= 2) {
    const data = webSheet.getDataRange().getValues();
    for (let i = 1; i < data.length; i++) {
      const row = data[i]; const studentId = String(row[1] || '').trim(); const period = String(row[4] || '').trim(); const dateRaw = row[5]; const inputType = String(row[6] || '').trim();
      if (!studentId || !period || !dateRaw || !inputType) continue;
      const dateKey = formatDateKey(dateRaw); const key = `${dateKey}|${period}|${studentId}`;
      if (inputType === '웹앱출석') { recordMap[key] = { studentId, dateKey, period, type: '출석' }; }
      if (inputType === '웹앱사유' && !recordMap[key]) { recordMap[key] = { studentId, dateKey, period, type: '사전제출' }; }
    }
  }

  const summary = {};
  Object.values(recordMap).forEach(r => {
    if (!summary[r.studentId]) {
      const info = studentMap[r.studentId] || {};
      summary[r.studentId] = { className: info.className || inferClassFromId_(r.studentId), studentId: r.studentId, name: info.name || '', present: 0, memo: 0, total: 0, finalAbsence: 0 };
    }
    if (r.type === '출석') { summary[r.studentId].present++; } else if (r.type === '사전제출') { summary[r.studentId].memo++; }
    summary[r.studentId].total++;
  });

  const approvedMap = {};
  if (approvalSheet && approvalSheet.getLastRow() >= 2) {
    const data = approvalSheet.getDataRange().getValues();
    for (let i = 1; i < data.length; i++) {
      const row = data[i]; const dateRaw = row[0]; const period = String(row[4] || '').trim(); const studentId = String(row[5] || '').trim(); const approved = String(row[8] || '').trim();
      if (!dateRaw || !period || !studentId) continue; if (approved !== 'Y') continue;
      approvedMap[`${formatDateKey(dateRaw)}|${period}|${studentId}`] = true;
    }
  }
  if (resultSheet && resultSheet.getLastRow() >= 2) {
    const data = resultSheet.getDataRange().getValues(); const countedAbsenceMap = {};
    for (let i = 1; i < data.length; i++) {
      const row = data[i]; const dateRaw = row[0]; const period = String(row[3] || '').trim(); const studentId = String(row[4] || '').trim(); const name = String(row[5] || '').trim(); const className = String(row[6] || '').trim(); const type = String(row[8] || '').trim(); const attendance = String(row[10] || '').trim();
      if (!dateRaw || !period || !studentId) continue; if (attendance !== '결석') continue; if (!(type === '자율학습' || type === '향상반')) continue;
      const dateKey = formatDateKey(dateRaw); const key = `${dateKey}|${period}|${studentId}`;
      if (approvedMap[key]) continue; if (countedAbsenceMap[key]) continue;
      countedAbsenceMap[key] = true;
      if (!summary[studentId]) { summary[studentId] = { className: className || inferClassFromId_(studentId), studentId, name, present: 0, memo: 0, total: 0, finalAbsence: 0 }; }
      summary[studentId].finalAbsence++;
    }
  }

  const rows = Object.values(summary).map(s => {
    const sincerity = s.total === 0 ? 0 : Math.round(((s.present + s.memo * 0.5) / s.total) * 1000) / 10 / 100;
    let level = '보통'; if (sincerity >= 0.9 && s.finalAbsence <= 1) { level = '우수'; } else if (sincerity < 0.7 || s.finalAbsence >= 3) { level = '주의'; }
    return [s.className, s.studentId, s.name, s.present, s.memo, s.total, sincerity, s.finalAbsence, level];
  });

  rows.sort((a, b) => {
    if (String(a[0]) !== String(b[0])) return String(a[0]).localeCompare(String(b[0]), 'ko');
    if (b[6] !== a[6]) return b[6] - a[6]; if (a[7] !== b[7]) return a[7] - b[7];
    return String(a[1]).localeCompare(String(b[1]), 'ko');
  });

  const header = ['반', '학번', '이름', '출석횟수', '사전제출횟수', '전체기록횟수', '성실도', '최종결석누계', '판정'];
  sheet.clear(); sheet.getRange(1, 1, 1, header.length).setValues([header]);
  if (rows.length > 0) {
    sheet.getRange(2, 1, rows.length, header.length).setValues(rows); sheet.getRange(2, 7, rows.length, 1).setNumberFormat('0.0%');
    for (let i = 0; i < rows.length; i++) {
      const level = rows[i][8]; const targetRow = i + 2;
      if (level === '우수') { sheet.getRange(targetRow, 9).setBackground('#e6f4ea'); }
      else if (level === '주의') { sheet.getRange(targetRow, 9).setBackground('#fce8e6'); }
      else { sheet.getRange(targetRow, 9).setBackground('#fef7e0'); }
    }
  }
  sheet.getRange(1, 1, 1, header.length).setFontWeight('bold').setBackground('#e8f0fe');
  sheet.autoResizeColumns(1, header.length);
}

// 참여명단입력(N월) 시트가 사라졌으므로, 이름/반은 NFC학생현황(buildStudentMasterMap_)에서 가져온다.
function buildStudentInfoMap_() {
  const master = buildStudentMasterMap_();
  const map = {};
  Object.keys(master).forEach(function (id) {
    map[id] = { name: master[id].name, className: master[id].className };
  });
  return map;
}

function inferClassFromId_(studentId) {
  const id = String(studentId || '').trim();
  if (id.length >= 1 && /^\d/.test(id)) { return id.substring(0, 1) + '반'; }
  return '';
}

// [Monthly_Stats.gs] 누적 현황 탭 - 월별 개인 출결 + 랭킹 API
// (주의: 현재 Code.gs/안드로이드 앱 어디서도 호출하지 않는 보조 함수다. 기존 코드에 있던
// 그대로 보존한다 - 참여명단과 무관하게 출결결과 시트만 읽으므로 수정할 필요가 없다.)

function getMonthlyStatsData(month) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const resultSheet = ss.getSheetByName('출결결과');
  if (!resultSheet) return { error: '출결결과 시트 없음' };

  const data = resultSheet.getDataRange().getValues();
  const headers = data[0];
  // 컬럼 인덱스
  const COL_DATE    = headers.indexOf('실제날짜');
  const COL_MONTH   = headers.indexOf('신청월');
  const COL_PERIOD  = headers.indexOf('타임');
  const COL_STNUM   = headers.indexOf('학번');
  const COL_NAME    = headers.indexOf('이름');
  const COL_CLASS   = headers.indexOf('반');
  const COL_TYPE    = headers.indexOf('참여구분');
  const COL_STATUS  = headers.indexOf('출결');

  // 월 슬롯 수집 (해당 월에 실제로 운영된 날짜×타임 목록)
  const slotSet = new Set();
  const studentMap = {};  // 학번 → { name, className, slots: { 'yyyy-MM-dd|n타임': 출결 } }

  for (let i = 1; i < data.length; i++) {
    const row = data[i];
    const rowMonth = String(row[COL_MONTH] || '').trim();
    if (rowMonth !== month) continue;

    const type    = String(row[COL_TYPE]   || '').trim();
    const status  = String(row[COL_STATUS] || '').trim();
    if (type !== '자율학습') continue;

    const dateRaw = row[COL_DATE];
    const dateKey = Utilities.formatDate(new Date(dateRaw), Session.getScriptTimeZone(), 'yyyy-MM-dd');
    const period  = String(row[COL_PERIOD] || '').trim();
    const stNum   = String(row[COL_STNUM]  || '').trim();
    const name    = String(row[COL_NAME]   || '').trim();
    const cls     = String(row[COL_CLASS]  || '').trim();

    const slotKey = `${dateKey}|${period}`;
    slotSet.add(slotKey);

    if (!studentMap[stNum]) {
      studentMap[stNum] = { name, className: cls, slots: {}, present: 0, late: 0, absent: 0, total: 0 };
    }
    // 출석 > 지각 > 결석 우선순위 (같은 슬롯에 중복 행 있을 경우 대비)
    const existing = studentMap[stNum].slots[slotKey];
    const priority = { '출석': 3, '지각': 2, '결석': 1 };
    if (!existing || (priority[status] || 0) > (priority[existing] || 0)) {
      studentMap[stNum].slots[slotKey] = status;
    }
  }

  // 슬롯 정렬
  const sortedSlots = Array.from(slotSet).sort();

  // 학생별 집계
  const students = [];
  Object.entries(studentMap).forEach(([stNum, info]) => {
    let present = 0, late = 0, absent = 0;
    sortedSlots.forEach(slot => {
      const s = info.slots[slot];
      if (s === '출석') present++;
      else if (s === '지각') late++;
      else absent++;  // 결석 또는 미기록
    });
    const total = sortedSlots.length;
    students.push({
      studentNum: stNum,
      name: info.name,
      className: info.className,
      present, late, absent: total - present - late,
      total,
      slots: sortedSlots.map(slot => info.slots[slot] || '결석')
    });
  });

  // 랭킹 정렬: 출석+지각 내림차순, 동점이면 결석 오름차순
  students.sort((a, b) => {
    const aScore = a.present + a.late;
    const bScore = b.present + b.late;
    if (bScore !== aScore) return bScore - aScore;
    return a.absent - b.absent;
  });

  const top10    = students.slice(0, 10);
  const bottom10 = students.slice(-10).reverse();

  return {
    month,
    slots: sortedSlots,
    students,
    top10,
    bottom10
  };
}

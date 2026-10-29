// [Date_Helpers.gs] 날짜 및 학번 문자열 가공 헬퍼

function cleanStudentId(id) {
  if (!id) return "";
  var str = String(id).trim();
  if (str.indexOf('.') !== -1) {
    str = str.split('.')[0]; // 소수점 강제 제거 (.0 방어)
  }
  return str;
}

function normalizeDateText(value) {
  if (Object.prototype.toString.call(value) === '[object Date]' && !isNaN(value)) {
    return Utilities.formatDate(value, Session.getScriptTimeZone(), 'yyyy-MM-dd');
  }
  return String(value).trim().replace(/\./g, '-').replace(/\s/g, '');
}

function normalizeMonth(value) {
  if (!value) return '';
  if (Object.prototype.toString.call(value) === '[object Date]' && !isNaN(value)) {
    return Utilities.formatDate(value, Session.getScriptTimeZone(), 'yyyy-MM');
  }
  var str = String(value).trim();
  if (/^\d{4}-\d{1,2}$/.test(str)) {
    var parts = str.split('-');
    return parts[0] + '-' + String(Number(parts[1])).padStart(2, '0');
  }
  var parsed = new Date(str);
  if (!isNaN(parsed.getTime())) { return Utilities.formatDate(parsed, Session.getScriptTimeZone(), 'yyyy-MM'); }
  return str;
}

function formatDateKey(value) { var d = toJsDate(value); return Utilities.formatDate(d, Session.getScriptTimeZone(), 'yyyy-MM-dd'); }
function parseDateKey(dateKey) { var p = dateKey.split('-').map(Number); return new Date(p[0], p[1] - 1, p[2]); }
function toJsDate(value) {
  if (Object.prototype.toString.call(value) === '[object Date]' && !isNaN(value)) { return new Date(value.getFullYear(), value.getMonth(), value.getDate()); }
  if (typeof value === 'number') {
    var epoch = new Date(Date.UTC(1899, 11, 30)); var ms = epoch.getTime() + value * 24 * 60 * 60 * 1000;
    var d = new Date(ms); return new Date(d.getFullYear(), d.getMonth(), d.getDate());
  }
  var d = new Date(value); return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

// 날짜 값(또는 yyyy-MM-dd 문자열) → 요일 한 글자. 참여명단입력(N월) 시트가 사라진 뒤로는
// 저장된 요일 칼럼이 없을 때(예: 교사가 직접 결석 처리할 때) 이걸로 채운다.
function dayOfWeekKo_(value) {
  var d = (value instanceof Date) ? value : parseDateKey(normalizeDateText(value));
  var days = ['일', '월', '화', '수', '목', '금', '토'];
  return days[d.getDay()];
}

// 학생이 QR/웹에서 "출석하기"를 누른 바로 그 순간의 날짜/요일/타임을 계산한다.
// ⚠️ 실시간 자가출석 전용이다 — 과거 기록을 다시 계산할 때는 절대 쓰지 않는다
// (저장된 신청월/요일/타임을 그대로 믿어야 한다. syncAttendance()의 buildUnifiedResponseData_ 참고).
function getWebActualTimeInfo(now) {
  var tz = Session.getScriptTimeZone();
  var dateText = Utilities.formatDate(now, tz, 'yyyy-MM-dd');
  var month = Utilities.formatDate(now, tz, 'yyyy-MM');
  var day = dayOfWeekKo_(now);

  var total = now.getHours() * 60 + now.getMinutes();
  var period;
  if (total < 18 * 60 + 20) {
    period = "1타임";       // ~18:19 → 1타임
  } else if (total < 20 * 60 + 15) {
    period = "2타임";       // 18:20~20:14 → 2타임
  } else {
    period = "3타임";       // 20:15~ → 3타임
  }

  return { dateText: dateText, month: month, day: day, period: period };
}

// QR기준시간 시트의 시작시간/지각기준 컬럼을 기준으로 정시/입실/지각을 판정한다.
// 참여명단과 무관하게 동작하므로 그대로 유지한다.
function getTimingInfo(qr, day, period, timeMap) {
  if (!qr) return { status: '', minutes: '' };
  var key = day + '|' + period;
  var entry = timeMap[key];
  if (!entry) return { status: '확인불가', minutes: '' };

  var startTime = (entry.start !== undefined) ? entry.start : entry;
  var lateTime  = (entry.late  !== undefined) ? entry.late  : entry;

  var submitTime = new Date(qr.timestamp);

  var baseStart = new Date(submitTime);
  baseStart.setHours(new Date(startTime).getHours(), new Date(startTime).getMinutes(), 0, 0);

  var baseLate = new Date(submitTime);
  baseLate.setHours(new Date(lateTime).getHours(), new Date(lateTime).getMinutes(), 0, 0);

  var diffMin = Math.round((submitTime - baseStart) / 60000);

  var status = '';
  if (diffMin <= 0)                status = '정시';
  else if (submitTime <= baseLate) status = '입실';
  else                              status = '지각';

  return { status: status, minutes: diffMin };
}

function formatDateTimeKo(date) {
  if (!date) return '';
  var d = new Date(date);
  var tz = Session.getScriptTimeZone();
  return Utilities.formatDate(d, tz, 'yyyy. M. d HH:mm:ss');
}

// [Date_Helpers.gs] 날짜/문자 가공 유틸리티

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
  if (!isNaN(parsed.getTime())) {
    return Utilities.formatDate(parsed, Session.getScriptTimeZone(), 'yyyy-MM');
  }
  return str;
}

function formatDateKey(value) {
  var d = toJsDate(value);
  return Utilities.formatDate(d, Session.getScriptTimeZone(), 'yyyy-MM-dd');
}

function parseDateKey(dateKey) {
  var parts = dateKey.split('-').map(Number);
  return new Date(parts[0], parts[1] - 1, parts[2]);
}

function toJsDate(value) {
  if (Object.prototype.toString.call(value) === '[object Date]' && !isNaN(value)) {
    return new Date(value.getFullYear(), value.getMonth(), value.getDate());
  }
  if (typeof value === 'number') {
    var epoch = new Date(Date.UTC(1899, 11, 30));
    var ms = epoch.getTime() + value * 24 * 60 * 60 * 1000;
    var d = new Date(ms);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
  }
  var d = new Date(value);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

function dayOfWeekKo(dateKey) {
  var days = ['일', '월', '화', '수', '목', '금', '토'];
  return days[parseDateKey(dateKey).getDay()];
}

function formatDateTimeKo(date) {
  if (!date) return '';
  var d = new Date(date);
  var tz = Session.getScriptTimeZone();
  return Utilities.formatDate(d, tz, 'yyyy. M. d HH:mm:ss');
}

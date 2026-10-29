package com.example.nfc

import android.app.DatePickerDialog
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.CircleShape
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class TeacherLogItem(
    val date: String,
    val studentNum: String,
    val name: String,
    val className: String,
    val slotName: String,
    val status: String,
    var reason: State<String>
)

data class StudentItem(
    val studentId: String,
    val name: String,
    val nfcId: String,
    val className: String
)

data class MonthlySlotEntry(
    val studentNum: String,
    val name: String,
    val className: String,
    val present: Int,
    val late: Int,
    val absent: Int,
    val total: Int,
    val slots: List<String>
)

data class MonthlyStats(
    val month: String,
    val slots: List<String>,
    val students: List<MonthlySlotEntry>,
    val top10: List<MonthlySlotEntry>,
    val bottom10: List<MonthlySlotEntry>
)

data class StudentAttendanceResult(
    val name: String,
    val className: String,
    val slotName: String,
    val timeString: String,
    val status: String,
    val message: String = ""
)

// NFC학생현황 명부의 학생 한 명. 참여명단입력(N월) 신청 개념이 사라졌으므로 요일별 신청상태를
// 들고 있던 옛 DayStudent 모델을 버리고, 학생 자체와 "오늘 하루치 상태"(DailyStatus)를 분리했다.
data class RosterStudent(
    val studentNum: String,
    val name: String,
    val className: String,
    val nfcId: String = ""
)

// 특정 날짜(selectedDate) 기준 한 학생의 방과후/야간1/야간2 상태. 더 이상 요일별로 저장하지
// 않고, 화면에서 고른 날짜 하나에 대한 상태만 들고 있다가 날짜를 바꾸면 서버에서 다시 받아온다.
data class DailyStatus(
    val afterSchool: String = "",
    val night1: String = "",
    val night2: String = ""
)

// 태깅/수동 처리를 시도했지만 서버(스프레드시트)에 실제로 반영됐는지 아직 확인되지 않은 기록.
// 새로고침/날짜 조회 시 스프레드시트의 실제 기록과 대조(reconcilePendingSends)해서, 반영이
// 안 된 게 있으면 저장했던 그대로(날짜/시간/상태) 다시 전송한다.
data class PendingRecord(
    val id: String,
    val studentNum: String,
    val name: String,
    val slotName: String,
    val status: String,
    val dateKey: String,
    val recordTime: String,
    val inputType: String
)

// 방과후/야간1/야간2 시간대 판정 — onNewIntent(NFC 태깅)와 StudentAttendanceScreen(안내 화면
// 문구) 두 곳에서 각자 하드코딩되어 있던 시간대를 여기 하나로 모았다.
private data class TimeWindow(
    val slot: String,
    val onTimeRange: IntRange,
    val lateRange: IntRange
)

private val TIME_WINDOWS = listOf(
    TimeWindow("방과후", 17 * 60..17 * 60 + 14, 17 * 60 + 15..18 * 60 + 30),
    TimeWindow("야간1", 19 * 60..19 * 60 + 14, 19 * 60 + 15..20 * 60 + 10),
    TimeWindow("야간2", 20 * 60 + 20..20 * 60 + 34, 20 * 60 + 35..21 * 60 + 30)
)

private fun resolveAttendanceSlot(totalMinutes: Int): Pair<String, String>? {
    for (w in TIME_WINDOWS) {
        if (totalMinutes in w.onTimeRange) return w.slot to "출석"
        if (totalMinutes in w.lateRange) return w.slot to "지각"
    }
    return null
}

private fun formatMinutesClock(total: Int): String = String.format("%02d:%02d", total / 60, total % 60)

private fun currentSlotDescription(totalMinutes: Int): String {
    for (w in TIME_WINDOWS) {
        if (totalMinutes in w.onTimeRange) return "${w.slot} 출석 (${formatMinutesClock(w.onTimeRange.first)} ~ ${formatMinutesClock(w.onTimeRange.last)})"
        if (totalMinutes in w.lateRange) return "${w.slot} 지각 (${formatMinutesClock(w.lateRange.first)} ~ ${formatMinutesClock(w.lateRange.last)})"
    }
    return "운영 시간 외"
}

private fun todayDateKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN).format(Calendar.getInstance().time)

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private var currentMenu = mutableStateOf("HOME")
    private var selectedDate = mutableStateOf(todayDateKey())
    private var selectedClass = mutableStateOf("전체")
    private var statusMessage = mutableStateOf("🔄 구글 시트 데이터 로딩을 대기하고 있습니다...")

    private var todayDateAndDayDisplay = ""

    // NFC학생현황 전체 명단 (요일/신청 여부와 무관하게 항상 전체가 보인다)
    private val masterStudentList = mutableStateListOf<RosterStudent>()
    // selectedDate 기준 학번별 오늘 상태. 날짜를 바꾸면 서버에서 새로 받아와 통째로 교체한다.
    private val dailyStatusMap = mutableStateMapOf<String, DailyStatus>()
    // 전송은 시도했지만 시트에 실제로 반영됐는지 아직 확인 못 한 기록들 (화면 상단에 건수 표시).
    private val pendingSends = mutableStateListOf<PendingRecord>()

    private val teacherConfirmList = mutableStateListOf<TeacherLogItem>()
    private val studentManagementList = mutableStateListOf<StudentItem>()
    private var managementSelectedClass = mutableStateOf(1)

    private var isWaitingNfcForAdd = mutableStateOf(false)
    private var pendingNewStudentData = mutableStateOf<Triple<String, String, String>?>(null)
    private var pendingNfcTagResult = mutableStateOf("")
    private var nfcEditTargetStudent = mutableStateOf<StudentItem?>(null)
    private var isWaitingNfcForEdit = mutableStateOf(false)
    private var teacherSelectedDate = mutableStateOf("")

    private var isStudentMode = mutableStateOf(false)
    private var studentModeResult = mutableStateOf<StudentAttendanceResult?>(null)
    private var lastTagTime = mutableStateOf(0L)
    private var showAdminPasswordDialog = mutableStateOf(false)

    private var statsData = mutableStateOf<MonthlyStats?>(null)
    private var statsLoading = mutableStateOf(false)
    private var statsError = mutableStateOf("")
    private var statsSelectedMonth = mutableStateOf("2026-07")
    private var statsSelectedClass = mutableStateOf("전체")
    private var statsTab = mutableStateOf(0)

    private val googleSheetUrl = "https://script.google.com/macros/s/AKfycbyf0Un2Znts7iZzcpujMhlQkS2nguAjD-zwyKQqQLOqo4YLZLq2kNROg593pHokzW1p/exec"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val calendar = Calendar.getInstance()
        todayDateAndDayDisplay = SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN).format(calendar.time)

        teacherSelectedDate.value = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN).format(calendar.time)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fetchStudentRosterFromGoogle()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F6F8)) {
                    if (showAdminPasswordDialog.value) {
                        AdminPasswordDialog(
                            onConfirm = {
                                showAdminPasswordDialog.value = false
                                isStudentMode.value = false
                            },
                            onDismiss = { showAdminPasswordDialog.value = false }
                        )
                    }

                    if (isStudentMode.value) {
                        StudentAttendanceScreen(
                            result = studentModeResult.value,
                            lastTagTime = lastTagTime.value,
                            onResultConsumed = { studentModeResult.value = null },
                            onExitStudentMode = { isStudentMode.value = false },
                            onShowPasswordDialog = { showAdminPasswordDialog.value = true }
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0984E3))
                                    .statusBarsPadding()
                                    .padding(14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("운호고 자율학습 출석부", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFAEA)),
                                border = BorderStroke(1.dp, Color(0xFFFFEAA7))
                            ) {
                                Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "📅 현재 시각: $todayDateAndDayDisplay", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0984E3))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = statusMessage.value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50), textAlign = TextAlign.Center)
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when (currentMenu.value) {
                                    "HOME" -> HomeScreen()
                                    "CALENDAR" -> TeacherConfirmScreen()
                                    "STATS" -> StatsScreen()
                                    "MANAGEMENT" -> ManagementScreen()
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White)
                                    .navigationBarsPadding()
                                    .padding(vertical = 8.dp)
                                    .border(BorderStroke(0.5.dp, Color(0xFFE0E0E0)))
                            ) {
                                val menus = listOf(
                                    "HOME" to "🏠\n출석체크",
                                    "CALENDAR" to "📋\n담임 확인",
                                    "STATS" to "📊\n누적현황",
                                    "MANAGEMENT" to "👤\n학생관리"
                                )
                                menus.forEach { (key, label) ->
                                    val isSelected = currentMenu.value == key
                                    Box(modifier = Modifier.weight(1f).clickable { currentMenu.value = key }.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                        Text(label.replace("\\n", "\n"), textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) Color(0xFF0984E3) else Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        if (isWaitingNfcForAdd.value && tag != null) {
            val idBytes = tag.id
            val rawHex = idBytes.joinToString("") { String.format("%02X", it) }.trim()
            val pending = pendingNewStudentData.value
            if (pending != null) {
                pendingNfcTagResult.value = rawHex
                addStudentToGoogle(pending.first, pending.second, rawHex, "${pending.third}반")
                pendingNewStudentData.value = null
            }
            isWaitingNfcForAdd.value = false
            statusMessage.value = "NFC 태깅 완료 → 등록 중..."
            return
        }

        if (isWaitingNfcForEdit.value && tag != null) {
            val idBytes = tag.id
            val rawHex = idBytes.joinToString("") { String.format("%02X", it) }.trim()
            val target = nfcEditTargetStudent.value
            if (target != null) {
                pendingNfcTagResult.value = rawHex
                updateStudentNfcToGoogle(target.studentId, rawHex)
            }
            isWaitingNfcForEdit.value = false
            statusMessage.value = "NFC 태깅 완료 → 업데이트 중..."
            return
        }

        if (tag != null) {
            val idBytes = tag.id
            val rawPhoneHex = idBytes.joinToString("") { String.format("%02X", it) }.trim()

            // 참여명단이 사라졌으므로 요일 비교 없이 NFC ID만으로 학생을 찾는다 - 등록된 학생이면
            // 날짜/요일과 무관하게 항상 태깅할 수 있다.
            val matchedStudent = masterStudentList.find { student ->
                val cleanSheetHex = student.nfcId.replace(":", "").replace(" ", "").trim()
                cleanSheetHex.isNotEmpty() && cleanSheetHex.equals(rawPhoneHex, ignoreCase = true)
            }

            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)
            val minute = now.get(Calendar.MINUTE)
            val timeString = String.format("%02d:%02d", hour, minute)
            val totalMinutes = hour * 60 + minute
            val todayKey = todayDateKey()

            if (matchedStudent != null) {
                val resolved = resolveAttendanceSlot(totalMinutes)

                if (resolved != null) {
                    val (processedSlot, sendStatus) = resolved

                    // NFC 태깅은 항상 "지금, 오늘" 실제로 벌어지는 일이다. 화면에 다른 날짜가
                    // 떠 있어도(담임이 과거 기록을 보정 중일 수 있으므로) 오늘 날짜로 서버에
                    // 저장하고, 화면 갱신은 오늘을 보고 있을 때만 즉시 반영한다.
                    if (selectedDate.value == todayKey) {
                        val current = dailyStatusMap[matchedStudent.studentNum] ?: DailyStatus()
                        val display = "$sendStatus ($timeString)"
                        dailyStatusMap[matchedStudent.studentNum] = when (processedSlot) {
                            "방과후" -> current.copy(afterSchool = display)
                            "야간1" -> current.copy(night1 = display)
                            "야간2" -> current.copy(night2 = display)
                            else -> current
                        }
                    }

                    statusMessage.value = "🎯 [NFC 태깅] 1-${matchedStudent.className} ${matchedStudent.name} $sendStatus 처리!"
                    Toast.makeText(this, "${matchedStudent.name} 학생 자습 참여 인정", Toast.LENGTH_SHORT).show()
                    if (isStudentMode.value) {
                        studentModeResult.value = StudentAttendanceResult(
                            name = matchedStudent.name,
                            className = "1-${matchedStudent.className}",
                            slotName = processedSlot,
                            timeString = timeString,
                            status = sendStatus
                        )
                        lastTagTime.value = System.currentTimeMillis()
                    }

                    val nfcRecordTime = "$timeString:00"
                    val nfcPendingId = "${matchedStudent.studentNum}|$processedSlot|$todayKey|${System.nanoTime()}"
                    pendingSends.add(PendingRecord(nfcPendingId, matchedStudent.studentNum, matchedStudent.name, processedSlot, sendStatus, todayKey, nfcRecordTime, "NFC"))
                    sendSingleLogToGoogleAsync(matchedStudent.studentNum, processedSlot, sendStatus, todayKey, nfcRecordTime, "NFC") { ok, _ ->
                        if (ok) pendingSends.removeAll { it.id == nfcPendingId }
                    }
                } else {
                    Toast.makeText(this, "${matchedStudent.name}: 자습 교시 운영 외 시간대 태깅입니다.", Toast.LENGTH_LONG).show()
                    if (isStudentMode.value) {
                        studentModeResult.value = StudentAttendanceResult(
                            name = matchedStudent.name,
                            className = "1-${matchedStudent.className}",
                            slotName = "시간외",
                            timeString = timeString,
                            status = "시간외"
                        )
                        lastTagTime.value = System.currentTimeMillis()
                    }
                }
            } else {
                statusMessage.value = "⚠️ [인증 오류] 등록되지 않은 카드입니다. (UID: $rawPhoneHex)"
                if (isStudentMode.value) {
                    studentModeResult.value = StudentAttendanceResult(
                        name = "미등록 카드",
                        className = "학생 정보 없음",
                        slotName = "인증 오류",
                        timeString = timeString,
                        status = "미등록",
                        message = "미등록 학생증입니다. 학생관리 탭에서 먼저 등록하세요."
                    )
                    lastTagTime.value = System.currentTimeMillis()
                }
                Toast.makeText(this, "미등록 카드 태그 감지", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Composable
    fun HomeScreen() {
        val context = LocalContext.current
        val dateSetListener = object : DatePickerDialog.OnDateSetListener {
            override fun onDateSet(view: android.widget.DatePicker?, year: Int, month: Int, dayOfMonth: Int) {
                val formattedMonth = String.format("%02d", month + 1)
                val formattedDay = String.format("%02d", dayOfMonth)
                val newDate = "$year-$formattedMonth-$formattedDay"
                selectedDate.value = newDate
                fetchAttendanceStatusForDate(newDate)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2ED573))
                    .clickable { isStudentMode.value = true }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📳 학생 출석 모드로 전환",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        val parts = selectedDate.value.split("-").map { it.toInt() }
                        DatePickerDialog(context, dateSetListener, parts[0], parts[1] - 1, parts[2]).show()
                    },
                    border = BorderStroke(1.dp, Color(0xFF0984E3))
                ) {
                    Text("📅 ${selectedDate.value}", fontWeight = FontWeight.Bold, color = Color(0xFF0984E3), fontSize = 13.sp)
                }

                if (selectedDate.value != todayDateKey()) {
                    TextButton(onClick = {
                        selectedDate.value = todayDateKey()
                        fetchAttendanceStatusForDate(todayDateKey())
                    }) {
                        Text("오늘로 돌아가기", fontSize = 12.sp, color = Color(0xFF0984E3))
                    }
                }
            }

            // 전송은 시도했지만 시트 반영이 아직 확인되지 않은 기록이 있으면 알려준다.
            // 🔄 새로고침을 누르면 시트와 대조해서 자동으로 다시 전송된다.
            val pendingForThisDate = pendingSends.count { it.dateKey == selectedDate.value }
            if (pendingForThisDate > 0) {
                Box(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "⏳ 시트 반영 확인 중 ${pendingForThisDate}건 - 🔄 새로고침하면 누락된 기록을 자동으로 다시 보냅니다.",
                        fontSize = 11.sp, color = Color(0xFF8A6D3B)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp)
                ) {
                    val classList = listOf("전체", "1반", "2반", "3반", "4반", "5반", "6반", "7반", "8반", "9반")
                    classList.forEach { cls ->
                        val isSelected = selectedClass.value == cls
                        val labelText = if (cls == "전체") "🌟 전체" else "1-${cls.replace("반", "")}"

                        Box(
                            modifier = Modifier
                                .clickable { selectedClass.value = cls }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = labelText,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isSelected) Color(0xFF0984E3) else Color.Gray,
                                fontSize = if (cls == "전체") 15.sp else 16.sp
                            )
                        }
                    }
                }

                Button(
                    onClick = { fetchStudentRosterFromGoogle() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF74B9FF)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 6.dp).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Text("🔄", fontSize = 14.sp, color = Color.White)
                }

                Button(
                    onClick = { sendAttendanceLogToGoogle() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ED573)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 12.dp).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Text("시트 전송 📤", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            val filteredStudents = masterStudentList.filter { student ->
                selectedClass.value == "전체" || student.className == selectedClass.value
            }.sortedBy { it.studentNum }

            val titleText = "📊 ${selectedDate.value} ${if (selectedClass.value == "전체") "전체" else selectedClass.value} 명단 (총 ${filteredStudents.size}명)"

            Text(
                text = titleText,
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                color = Color.Gray
            )

            if (filteredStudents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("등록된 학생이 없습니다.\n학생관리 탭에서 먼저 등록해주세요.".replace("\\n", "\n"), color = Color.Gray, textAlign = TextAlign.Center, fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    items(filteredStudents) { student ->
                        val status = dailyStatusMap[student.studentNum] ?: DailyStatus()
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(0.5.dp, Color(0xFFE0E0E0))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1.2f)) {
                                    Text(student.studentNum, fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(student.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3436))
                                }

                                Row(modifier = Modifier.weight(2.8f), horizontalArrangement = Arrangement.End) {
                                    DropdownTimeBadge("방과후", status.afterSchool) { selectedStatus ->
                                        updateStudentStatus(student, "방과후", selectedStatus)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    DropdownTimeBadge("야간1", status.night1) { selectedStatus ->
                                        updateStudentStatus(student, "야간1", selectedStatus)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    DropdownTimeBadge("야간2", status.night2) { selectedStatus ->
                                        updateStudentStatus(student, "야간2", selectedStatus)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun TeacherConfirmScreen() {
        val context = LocalContext.current
        val dateSetListener = object : DatePickerDialog.OnDateSetListener {
            override fun onDateSet(view: android.widget.DatePicker?, year: Int, month: Int, dayOfMonth: Int) {
                val formattedMonth = String.format("%02d", month + 1)
                val formattedDay = String.format("%02d", dayOfMonth)
                teacherSelectedDate.value = "$year-$formattedMonth-$formattedDay"
                fetchAttendanceLogsFromGoogle(teacherSelectedDate.value, selectedClass.value)
            }
        }

        val calendar = Calendar.getInstance()
        val datePickerDialog = DatePickerDialog(
            context, dateSetListener,
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = { datePickerDialog.show() }, border = BorderStroke(1.dp, Color(0xFF0984E3))) {
                    Text(text = "📅 날짜 변경: ${teacherSelectedDate.value}", fontWeight = FontWeight.Bold, color = Color(0xFF0984E3))
                }

                Button(
                    onClick = { fetchAttendanceLogsFromGoogle(teacherSelectedDate.value, selectedClass.value) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0984E3))
                ) {
                    Text("명단 조회 🔍", fontSize = 13.sp, color = Color.White)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)
            ) {
                listOf("1반", "2반", "3반", "4반", "5반", "6반", "7반", "8반", "9반").forEach { cls ->
                    val isSelected = selectedClass.value == cls
                    Box(
                        modifier = Modifier.clickable {
                            selectedClass.value = cls
                            fetchAttendanceLogsFromGoogle(teacherSelectedDate.value, cls)
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "1-${cls.replace("반", "")}",
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF0984E3) else Color.Gray,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (teacherConfirmList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("해당 날짜 및 학반의 출결 적재 로그가 존재하지 않습니다.\n상단 '명단 조회' 버튼을 눌러주세요.".replace("\\n", "\n"), color = Color.Gray, textAlign = TextAlign.Center)
                }
            } else {
                val groupedLogs = teacherConfirmList.groupBy { it.slotName }
                val slotOrder = listOf("방과후", "야간1", "야간2")
                val sortedSlots = groupedLogs.keys.sortedBy { slotOrder.indexOf(it).takeIf { idx -> idx != -1 } ?: 99 }

                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    sortedSlots.forEach { slot ->
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp, bottom = 6.dp, start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🕒 $slot",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF0984E3)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFE8F0FE))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${groupedLogs[slot]?.size ?: 0}명",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0984E3)
                                    )
                                }
                            }
                        }

                        items(groupedLogs[slot] ?: emptyList()) { logItem ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(0.5.dp, Color(0xFFE0E0E0))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1.3f)) {
                                        Text(text = "${logItem.studentNum} ${logItem.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = "교시 구분: ${logItem.slotName}", fontSize = 12.sp, color = Color.Gray)
                                    }

                                    Row(
                                        modifier = Modifier.weight(2.7f),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isAbsent = logItem.status == "결석"
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isAbsent) Color(0xFFEA2027) else Color(0xFF2ED573))
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(text = logItem.status, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        if (isAbsent) {
                                            TeacherReasonDropdown(logItem)
                                        } else {
                                            Text(text = "확인 불필요", fontSize = 11.sp, color = Color.LightGray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    @Composable
    fun TeacherReasonDropdown(item: TeacherLogItem) {
        var isExpanded by remember { mutableStateOf(false) }
        val reasons = listOf("담임허락", "학부모 확인", "병원 진료", "조퇴", "학원 보강", "향상반 참여", "학사 이동", "기타")
        val currentReasonText = item.reason.value

        Box {
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(BorderStroke(1.dp, Color(0xFF74B9FF)))
                    .background(if (currentReasonText.isEmpty()) Color(0xFFF1F2F6) else Color(0xFFFFFAEA))
                    .clickable { isExpanded = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (currentReasonText.isEmpty()) "사유 선택 🔽" else currentReasonText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (currentReasonText.isEmpty()) Color.Gray else Color(0xFF0984E3),
                    maxLines = 1
                )
            }

            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = { isExpanded = false },
                modifier = Modifier.background(Color.White).width(140.dp)
            ) {
                reasons.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(text = r, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                        onClick = {
                            isExpanded = false
                            updateAbsenceReasonOnGoogle(item, r)
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun DropdownTimeBadge(slotName: String, status: String, onStatusSelected: (String) -> Unit) {
        val cleanStatus = status.trim()
        var isMenuExpanded by remember { mutableStateOf(false) }

        val isCheckedIn = cleanStatus.contains("출석")
        val isLate = cleanStatus.contains("지각")
        val isAbsent = cleanStatus == "결석"
        val isNotEnrolled = cleanStatus.isEmpty()

        val bgContainerColor = when {
            isCheckedIn -> Color(0xFF2ED573) // 초록 (출석)
            isLate -> Color(0xFFFFA502)      // 주황 (지각)
            isAbsent -> Color(0xFFEA2027)    // 빨강 (결석)
            cleanStatus == "학사" -> Color(0xFF10AC84) // 청록 (학사)
            else -> Color(0xFFF1F2F6).copy(alpha = 0.6f)
        }

        val contentTextColor = if (isNotEnrolled) Color(0xFFA4B0BE) else Color.White
        val displayText = if (isNotEnrolled) "미기록" else cleanStatus

        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgContainerColor)
                    .clickable { isMenuExpanded = true }
                    .padding(vertical = 6.dp)
            ) {
                Text(text = slotName, fontSize = 10.sp, color = if (!isNotEnrolled) Color.White.copy(alpha = 0.85f) else Color.Gray)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = displayText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = contentTextColor, textAlign = TextAlign.Center)
            }

            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false },
                modifier = Modifier.background(Color.White).width(140.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("✅ 출석 처리", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2ED573)) },
                    onClick = { onStatusSelected("출석"); isMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("🕒 지각 처리", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA502)) },
                    onClick = { onStatusSelected("지각"); isMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("❌ 결석 처리", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEA2027)) },
                    onClick = { onStatusSelected("결석"); isMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("🏛️ 학사 처리", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10AC84)) },
                    onClick = { onStatusSelected("학사"); isMenuExpanded = false }
                )
                HorizontalDivider(color = Color(0xFFF1F2F6))
                DropdownMenuItem(
                    text = { Text("🔄 기록 지우기", fontSize = 12.sp, color = Color.Gray) },
                    onClick = { onStatusSelected("리셋"); isMenuExpanded = false }
                )
            }
        }
    }

    @Composable
    fun AdminPasswordDialog(
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        var input by remember { mutableStateOf("") }
        var isError by remember { mutableStateOf(false) }
        val correctPassword = "6688"

        AlertDialog(
            onDismissRequest = { onDismiss() },
            title = {
                Text(
                    text = "🔐 관리자 인증",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Column {
                    Text(
                        text = "관리자 비밀번호를 입력하세요.",
                        fontSize = 14.sp,
                        color = Color(0xFF636E72)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            isError = false
                        },
                        label = { Text("비밀번호") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = isError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isError) {
                        Text(
                            text = "비밀번호가 틀렸습니다.",
                            color = Color(0xFFD63031),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (input == correctPassword) {
                            onConfirm()
                        } else {
                            isError = true
                            input = ""
                        }
                    }
                ) {
                    Text("확인", fontWeight = FontWeight.Bold, color = Color(0xFF0984E3))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismiss() }) {
                    Text("취소", color = Color(0xFF636E72))
                }
            }
        )
    }

    @Composable
    fun StudentAttendanceScreen(
        result: StudentAttendanceResult?,
        lastTagTime: Long,
        onResultConsumed: () -> Unit,
        onExitStudentMode: () -> Unit,
        onShowPasswordDialog: () -> Unit
    ) {
        LaunchedEffect(lastTagTime) {
            if (lastTagTime > 0L) {
                delay(4000)
                onResultConsumed()
            }
        }

        val infiniteTransition = rememberInfiniteTransition(label = "nfc_pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.85f, targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "pulse_scale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.3f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "pulse_alpha"
        )

        val now = Calendar.getInstance()
        val totalMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val currentSlot = currentSlotDescription(totalMins)
        val isActiveTime = currentSlot != "운영 시간 외"

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A3D62))
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { onShowPasswordDialog() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("관리자 모드", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
            }

            if (result == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "운호고 야자출결",
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isActiveTime) Color(0xFF2ED573) else Color(0xFFEA2027))
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = currentSlot,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size((120 * pulseScale).dp)
                                .clip(CircleShape)
                                .background(Color(0xFF74B9FF).copy(alpha = pulseAlpha * 0.3f))
                        )
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0984E3).copy(alpha = 0.6f))
                        )
                        Text(
                            text = "📳",
                            fontSize = 44.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(36.dp))
                    Text(
                        text = if (isActiveTime) "학생증을 태깅하세요" else "현재 운영 시간이 아닙니다",
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (isActiveTime) Color.White else Color(0xFFFF7675)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isActiveTime) "NFC 카드를 기기 뒷면에 가까이 대세요" else "야자 운영 시간에 다시 시도해주세요",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val bgColor = when (result.status) {
                    "출석" -> Color(0xFF00B894)
                    "지각" -> Color(0xFFE17055)
                    "미등록" -> Color(0xFF6C5CE7)
                    else  -> Color(0xFFD63031)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(bgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (result.status) {
                                "출석" -> "✅"
                                "지각" -> "⚠️"
                                "미등록" -> "🪪"
                                else  -> "❌"
                            },
                            fontSize = 52.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = when (result.status) {
                            "출석" -> "출석 확인 완료"
                            "지각" -> "지각 처리됨"
                            "미등록" -> "미등록 학생증"
                            else  -> "운영 시간 외 태깅"
                        },
                        fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(24.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(result.className, fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(result.name, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (result.message.isNotEmpty()) result.message else result.slotName,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                color = bgColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = result.timeString,
                                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "잠시 후 다음 학생 태깅을 기다립니다...",
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    @Composable
    fun StatsScreen() {
        val data = statsData.value
        val loading = statsLoading.value
        val error = statsError.value
        val selectedMonth = statsSelectedMonth.value
        val currentTab = statsTab.value

        LaunchedEffect(selectedMonth) { fetchMonthlyStats(selectedMonth) }

        Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F6F8))) {

            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val monthList = listOf(
                    "2026-05" to "5월",
                    "2026-06" to "6월",
                    "2026-07" to "7월",
                    "2026-08" to "8월",
                    "2026-09" to "9월",
                    "2026-10" to "10월",
                    "2026-11" to "11월",
                    "2026-12" to "12월"
                )
                monthList.forEach { (key, label) ->
                    val sel = selectedMonth == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (sel) Color(0xFF0984E3) else Color(0xFFF1F2F6))
                            .clickable { statsSelectedMonth.value = key }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (sel) Color.White else Color.Gray) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White)
                    .border(BorderStroke(0.5.dp, Color(0xFFE0E0E0)))
            ) {
                listOf("📋 개인현황", "🏆 상위 10명", "⚠️ 하위 10명").forEachIndexed { idx, label ->
                    val sel = currentTab == idx
                    Box(
                        modifier = Modifier.weight(1f).clickable { statsTab.value = idx }
                            .padding(vertical = 10.dp)
                            .then(if (sel) Modifier.border(BorderStroke(2.dp, Color(0xFF0984E3)), RoundedCornerShape(0.dp)) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (sel) Color(0xFF0984E3) else Color.Gray)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF0984E3))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("데이터 로딩 중...", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                    error.isNotEmpty() -> {
                        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠️", fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(error, color = Color(0xFFD63031), fontSize = 13.sp, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { fetchMonthlyStats(statsSelectedMonth.value) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0984E3))) {
                                Text("다시 시도")
                            }
                        }
                    }
                    data == null -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📊", fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("월을 선택하면 데이터를 불러옵니다.", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                    else -> {
                        when (currentTab) {
                            0 -> IndividualStatsContent(data.slots, data.students)
                            1 -> RankingContent(data.top10, data.slots, isTop = true)
                            2 -> RankingContent(data.bottom10, data.slots, isTop = false)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun IndividualStatsContent(slots: List<String>, students: List<MonthlySlotEntry>) {
        val classList = students.map { it.className }.distinct().sortedWith(
            compareBy { it.replace("반","").toIntOrNull() ?: 99 }
        )
        var selectedClassTab by remember { mutableStateOf(classList.firstOrNull() ?: "") }

        val filteredStudents = students
            .filter { it.className == selectedClassTab }
            .sortedBy { it.studentNum }

        val dateGroups = slots.groupBy { it.split("|")[0] }.entries.sortedBy { it.key }
        val totalSlotCols = slots.size

        Column(modifier = Modifier.fillMaxSize()) {

            LazyRow(
                modifier = Modifier.fillMaxWidth().background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(classList) { cls ->
                    val sel = selectedClassTab == cls
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) Color(0xFF0984E3) else Color(0xFFF1F2F6))
                            .clickable { selectedClassTab = cls }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(cls, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = if (sel) Color.White else Color.Gray) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    Color(0xFF00B894) to "출석",
                    Color(0xFFE17055) to "지각",
                    Color(0xFF74B9FF) to "인정",
                    Color(0xFFDFE6E9) to "결석"
                ).forEach { (color, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        Text(label, fontSize = 11.sp, color = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text("${filteredStudents.size}명 · 총 ${totalSlotCols}타임", fontSize = 11.sp, color = Color.Gray)
            }

            val nameColWidth = 76.dp
            val summaryColWidth = 58.dp
            val cellSize = 22.dp

            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF2C3E50))
                    .padding(vertical = 6.dp)
            ) {
                Text("이름", modifier = Modifier.width(nameColWidth).padding(start = 10.dp),
                    fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    dateGroups.forEach { (date, periodSlots) ->
                        val mmdd = date.substring(5).replace("-", "/")
                        Column(
                            modifier = Modifier.width(cellSize * periodSlots.size),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(mmdd, fontSize = 8.sp, color = Color.White.copy(0.9f),
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Row {
                                periodSlots.forEach { slot ->
                                    Text(
                                        text = slot.split("|")[1].replace("타임",""),
                                        modifier = Modifier.width(cellSize),
                                        fontSize = 8.sp, color = Color.White.copy(0.6f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    Text("출/지/결", modifier = Modifier.width(summaryColWidth),
                        fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                }
            }

            val sharedScrollState = rememberScrollState()

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredStudents) { rowIdx, student ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (rowIdx % 2 == 0) Color.White else Color(0xFFF8F9FA)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${student.studentNum.takeLast(2)}번\n${student.name}".replace("\\n", "\n"),
                            modifier = Modifier
                                .width(nameColWidth)
                                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                            fontSize = 9.sp, color = Color(0xFF2C3E50),
                            maxLines = 2,
                            lineHeight = 12.sp
                        )
                        Row(modifier = Modifier.horizontalScroll(sharedScrollState)) {
                            student.slots.forEach { status ->
                                val bgColor = when (status) {
                                    "출석" -> Color(0xFF00B894)
                                    "지각" -> Color(0xFFE17055)
                                    "인정", "담임허락", "병원진료", "향상반 참여", "학사 이동", "학사" -> Color(0xFF74B9FF)
                                    else   -> Color(0xFFDFE6E9)
                                }
                                Box(
                                    modifier = Modifier
                                        .width(cellSize).height(28.dp)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(bgColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (status == "지각") Text("지", fontSize = 7.sp, color = Color.White)
                                    else if (status in listOf("인정", "담임허락", "병원진료", "향상반 참여", "학사 이동", "학사")) Text("인", fontSize = 7.sp, color = Color.White)
                                }
                            }
                            Box(
                                modifier = Modifier.width(summaryColWidth).height(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${student.present}/${student.late}/${student.absent}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF2C3E50),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Divider(color = Color(0xFFF1F2F6), thickness = 0.5.dp)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    @Composable
    fun RankingContent(students: List<MonthlySlotEntry>, slots: List<String>, isTop: Boolean) {
        val accentColor = if (isTop) Color(0xFF0984E3) else Color(0xFFD63031)
        val titleText  = if (isTop) "🏆 참여 상위 10명" else "⚠️ 참여 하위 10명"

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(titleText, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
                Text("전체 ${slots.size}타임 기준", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
            }
            itemsIndexed(students) { idx, student ->
                val rank = idx + 1
                val rankColor = when {
                    isTop && rank == 1 -> Color(0xFFFFD700)
                    isTop && rank == 2 -> Color(0xFFB0BEC5)
                    isTop && rank == 3 -> Color(0xFFCD7F32)
                    else -> accentColor.copy(alpha = 0.7f)
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(rankColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$rank", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(student.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2C3E50))
                                Text(student.className, fontSize = 12.sp, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            val displaySlots = student.slots.take(30)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                displaySlots.forEach { status ->
                                    Box(
                                        modifier = Modifier.size(12.dp).clip(RoundedCornerShape(2.dp))
                                            .background(when (status) {
                                                "출석" -> Color(0xFF00B894)
                                                "지각" -> Color(0xFFE17055)
                                                else   -> Color(0xFFDFE6E9)
                                            })
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${student.present + student.late}/${student.total}",
                                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
                            Text("출석 ${student.present} · 지각 ${student.late} · 결석 ${student.absent}",
                                fontSize = 10.sp, color = Color.Gray)
                            val rate = if (student.total > 0) (student.present + student.late) * 100 / student.total else 0
                            Text("참여율 $rate%", fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (rate >= 80) Color(0xFF00B894) else if (rate >= 50) Color(0xFFE17055) else Color(0xFFD63031))
                        }
                    }
                    val dateSlots = slots.zip(student.slots).groupBy { it.first.split("|")[0] }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        dateSlots.entries.sortedBy { it.key }.take(15).forEach { (date, periodPairs) ->
                            val mmdd = date.substring(5).replace("-", "/")
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(mmdd, fontSize = 7.sp, color = Color.Gray)
                                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    periodPairs.forEach { (_, status) ->
                                        Box(
                                            modifier = Modifier.size(8.dp).clip(RoundedCornerShape(1.dp))
                                                .background(when (status) {
                                                    "출석" -> Color(0xFF00B894)
                                                    "지각" -> Color(0xFFE17055)
                                                    else   -> Color(0xFFDFE6E9)
                                                })
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    @Composable
    fun BlankScreen(title: String) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "$title\nNFC 출석 메커니즘 검증 완료 후 개방됩니다.".replace("\\n", "\n"), textAlign = TextAlign.Center, color = Color.LightGray, fontSize = 14.sp)
        }
    }

    @Composable
    fun ManagementScreen() {
        var showAddDialog by remember { mutableStateOf(false) }
        var inputStudentId by remember { mutableStateOf("") }
        var inputName by remember { mutableStateOf("") }
        val nfcTagResult by pendingNfcTagResult
        val nfcEditTarget by nfcEditTargetStudent
        val waitingNfcEdit by isWaitingNfcForEdit
        var deleteTargetId by remember { mutableStateOf<String?>(null) }

        val inputNfcId = remember { mutableStateOf("") }
        LaunchedEffect(nfcTagResult) {
            if (nfcTagResult.isNotEmpty() && showAddDialog) {
                inputNfcId.value = nfcTagResult
            }
        }
        val nfcEditInputId = remember { mutableStateOf("") }
        LaunchedEffect(nfcTagResult) {
            if (nfcEditInputId.value.isEmpty() && nfcTagResult.isNotEmpty() && nfcEditTarget != null) {
                nfcEditInputId.value = nfcTagResult
            }
        }

        val filteredStudents = studentManagementList
            .filter { it.className == "${managementSelectedClass.value}반" }
            .sortedBy { it.studentId }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("학생 관리", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0984E3))
                Button(
                    onClick = { fetchStudentListFromGoogle() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF74B9FF)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text("새로고침", fontSize = 13.sp, color = Color.White) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp)
            ) {
                listOf(1, 2, 3, 4, 5, 6, 7, 8, 9).forEach { cls ->
                    val isSelected = managementSelectedClass.value == cls
                    Box(
                        modifier = Modifier
                            .clickable { managementSelectedClass.value = cls }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "1-$cls",
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF0984E3) else Color.Gray,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "1-${managementSelectedClass.value}반  총 ${filteredStudents.size}명",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray
                )
                Button(
                    onClick = {
                        inputStudentId = ""
                        inputName = ""
                        inputNfcId.value = ""
                        pendingNfcTagResult.value = ""
                        showAddDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ED573)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text("+ 학생 추가", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }

            if (filteredStudents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "등록된 학생이 없습니다.\n+ 학생 추가 버튼으로 등록하세요.".replace("\\n", "\n"),
                        color = Color.LightGray, textAlign = TextAlign.Center, fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    items(filteredStudents) { student ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(0.5.dp, Color(0xFFE0E0E0))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(student.studentId, fontSize = 12.sp, color = Color.Gray)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(student.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3436))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (student.nfcId.isEmpty()) "NFC 미등록" else "NFC: ${student.nfcId}",
                                        fontSize = 11.sp,
                                        color = if (student.nfcId.isEmpty()) Color(0xFFEA2027) else Color(0xFF2ED573)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (student.nfcId.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFE8F5E9))
                                                .clickable {
                                                    nfcEditTargetStudent.value = student
                                                    nfcEditInputId.value = ""
                                                    pendingNfcTagResult.value = ""
                                                    isWaitingNfcForEdit.value = true
                                                    statusMessage.value = "${student.name} 학생증 NFC를 태깅하세요..."
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Text("NFC 등록", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFFFEBEE))
                                            .clickable { deleteTargetId = student.studentId }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text("삭제", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEA2027))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                    isWaitingNfcForAdd.value = false
                    pendingNfcTagResult.value = ""
                },
                title = { Text("학생 추가", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = inputStudentId,
                            onValueChange = { inputStudentId = it },
                            label = { Text("학번") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = inputName,
                            onValueChange = { inputName = it },
                            label = { Text("이름") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isWaitingNfcForAdd.value) Color(0xFFFFFAEA) else Color(0xFFF5F6F8))
                                .border(
                                    BorderStroke(1.dp, if (isWaitingNfcForAdd.value) Color(0xFFFF9800) else Color(0xFFE0E0E0)),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    if (!isWaitingNfcForAdd.value) {
                                        pendingNewStudentData.value = Triple(inputStudentId, inputName, managementSelectedClass.value.toString())
                                        isWaitingNfcForAdd.value = true
                                        statusMessage.value = "학생증 NFC를 기기에 태깅하세요..."
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (isWaitingNfcForAdd.value) "📳 학생증을 태깅하세요..." else "NFC UID 태깅하기",
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = if (isWaitingNfcForAdd.value) Color(0xFFFF9800) else Color(0xFF0984E3),
                                    textAlign = TextAlign.Center
                                )
                                if (inputNfcId.value.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("✅ ${inputNfcId.value}", fontSize = 12.sp, color = Color(0xFF2ED573), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text("* NFC UID는 선택사항입니다. 나중에 추가 가능합니다.", fontSize = 11.sp, color = Color.Gray)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (inputStudentId.isBlank() || inputName.isBlank()) {
                                statusMessage.value = "학번과 이름을 입력해주세요."
                                return@Button
                            }
                            addStudentToGoogle(inputStudentId.trim(), inputName.trim(), inputNfcId.value.trim(), "${managementSelectedClass.value}반")
                            showAddDialog = false
                            isWaitingNfcForAdd.value = false
                            pendingNfcTagResult.value = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0984E3))
                    ) { Text("등록", color = Color.White) }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        showAddDialog = false
                        isWaitingNfcForAdd.value = false
                        pendingNfcTagResult.value = ""
                    }) {
                        Text("취소")
                    }
                }
            )
        }

        deleteTargetId?.let { targetId ->
            val target = studentManagementList.find { it.studentId == targetId }
            AlertDialog(
                onDismissRequest = { deleteTargetId = null },
                title = { Text("학생 삭제", fontWeight = FontWeight.ExtraBold) },
                text = { Text("${target?.name ?: targetId} 학생을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.".replace("\\n", "\n")) },
                confirmButton = {
                    Button(
                        onClick = { deleteStudentFromGoogle(targetId); deleteTargetId = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA2027))
                    ) { Text("삭제", color = Color.White) }
                },
                dismissButton = {
                    OutlinedButton(onClick = { deleteTargetId = null }) { Text("취소") }
                }
            )
        }

        nfcEditTarget?.let { target ->
            AlertDialog(
                onDismissRequest = {
                    nfcEditTargetStudent.value = null
                    isWaitingNfcForEdit.value = false
                    pendingNfcTagResult.value = ""
                    nfcEditInputId.value = ""
                },
                title = { Text("NFC 등록", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "${target.studentId}  ${target.name}",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3436)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (waitingNfcEdit) Color(0xFFFFFAEA) else Color(0xFFF5F6F8))
                                .border(
                                    BorderStroke(1.dp, if (waitingNfcEdit) Color(0xFFFF9800) else Color(0xFFE0E0E0)),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    if (!waitingNfcEdit) {
                                        nfcEditInputId.value = ""
                                        pendingNfcTagResult.value = ""
                                        isWaitingNfcForEdit.value = true
                                        statusMessage.value = "${target.name} 학생증 NFC를 태깅하세요..."
                                    }
                                }
                                .padding(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (waitingNfcEdit) "📳 학생증을 태깅하세요..." else "NFC UID 태깅하기",
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = if (waitingNfcEdit) Color(0xFFFF9800) else Color(0xFF0984E3),
                                    textAlign = TextAlign.Center
                                )
                                if (nfcEditInputId.value.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "UID: ${nfcEditInputId.value}",
                                        fontSize = 13.sp, color = Color(0xFF2ED573),
                                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (nfcEditInputId.value.isNotEmpty()) {
                                updateStudentNfcToGoogle(target.studentId, nfcEditInputId.value)
                                nfcEditTargetStudent.value = null
                                isWaitingNfcForEdit.value = false
                                pendingNfcTagResult.value = ""
                                nfcEditInputId.value = ""
                            }
                        },
                        enabled = nfcEditInputId.value.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0984E3))
                    ) { Text("등록 완료", color = Color.White) }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        nfcEditTargetStudent.value = null
                        isWaitingNfcForEdit.value = false
                        pendingNfcTagResult.value = ""
                        nfcEditInputId.value = ""
                    }) { Text("취소") }
                }
            )
        }
    }

    // 담임이 HOME 탭에서 배지를 눌러 수동으로 처리한다. selectedDate.value를 그대로 쓰기 때문에,
    // 지난 날짜를 화면에 띄워놓고 정정해도 그 날짜 그대로 서버에 저장된다.
    // (과거 버그: 서버가 날짜를 전혀 받지 않아 항상 "지금"으로 저장되어, 나중에 지난 기록을
    // 고쳐도 통계에 반영되지 않았다.)
    private fun updateStudentStatus(student: RosterStudent, slot: String, action: String) {
        // 수동 처리는 "지금 누른 시각"이 아니라 그 차시의 기준시간으로 기록한다. 담임이 한참
        // 지난 뒤에(예: 밤 9시에 방과후 기록을 정정) 눌러도, 그 순간의 시각이 아니라 방과후
        // 출석/지각 기준시간이 그대로 남아야 하기 때문이다. NFC 태깅은 실제 태깅 시각을 그대로
        // 쓴다 - 그래야 출결결과의 '차이시간(분)'(몇 분 늦었는지)이 의미를 유지한다.
        val window = TIME_WINDOWS.find { it.slot == slot }
        val baseMinutes = when (action) {
            "지각" -> window?.lateRange?.first
            else -> window?.onTimeRange?.first
        } ?: run {
            val now = Calendar.getInstance()
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        }
        val timeString = formatMinutesClock(baseMinutes)
        val recordTime = "$timeString:00"

        val finalStatus = when (action) {
            "출석" -> "출석 ($timeString)"
            "지각" -> "지각 ($timeString)"
            "결석" -> "결석"
            "학사" -> "학사"
            "리셋" -> ""
            else -> ""
        }

        val current = dailyStatusMap[student.studentNum] ?: DailyStatus()
        dailyStatusMap[student.studentNum] = when (slot) {
            "방과후" -> current.copy(afterSchool = finalStatus)
            "야간1" -> current.copy(night1 = finalStatus)
            "야간2" -> current.copy(night2 = finalStatus)
            else -> current
        }

        statusMessage.value = "🎯 [수동 변경] ${student.name} -> $slot [$action] 반영 (${selectedDate.value})"

        // '리셋'도 서버에 보내서 실제로 지운다. (과거 버그: 리셋은 화면에서만 지워지고 서버에는
        // 전달되지 않아 새로고침하면 되돌아왔다.) 전송 확인 전까지는 pendingSends에 남겨둬서
        // 다음 새로고침/날짜 조회 때 시트와 대조해 누락됐으면 다시 보낸다.
        if (action in listOf("출석", "지각", "결석", "학사", "리셋")) {
            val pendingId = "${student.studentNum}|$slot|${selectedDate.value}|${System.nanoTime()}"
            pendingSends.add(PendingRecord(pendingId, student.studentNum, student.name, slot, action, selectedDate.value, recordTime, "수동"))
            sendSingleLogToGoogleAsync(student.studentNum, slot, action, selectedDate.value, recordTime, "수동") { ok, _ ->
                if (ok) pendingSends.removeAll { it.id == pendingId }
            }
        }
    }

    private fun fetchStudentRosterFromGoogle() {
        statusMessage.value = "🔄 학생 명부 동기화 중..."
        val queue = Volley.newRequestQueue(this)
        val requestUrl = "$googleSheetUrl?mode=GET_MASTER"

        val stringRequest = object : StringRequest(Request.Method.GET, requestUrl,
            { response ->
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.optString("result", "fail") == "success") {
                        val dataArray = jsonResponse.optJSONArray("data")
                        if (dataArray != null) {
                            masterStudentList.clear()
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.optJSONObject(i) ?: continue
                                masterStudentList.add(
                                    RosterStudent(
                                        studentNum = obj.optString("studentId", ""),
                                        name = obj.optString("name", ""),
                                        className = obj.optString("className", ""),
                                        nfcId = obj.optString("nfcId", "")
                                    )
                                )
                            }
                            statusMessage.value = "✅ 학생 명부 동기화 완료! 당일 기록 확인 중..."
                            fetchAttendanceStatusForDate(selectedDate.value)
                        }
                    }
                } catch (e: Exception) {
                    statusMessage.value = "⚠️ 명부 데이터 해석 오류 발생"
                }
            },
            { statusMessage.value = "❌ 구글 중앙 서버 연결 실패" }
        ) {
            override fun getHeaders(): MutableMap<String, String> {
                val headers = HashMap<String, String>()
                headers["User-Agent"] = "Mozilla/5.0"
                return headers
            }
        }
        stringRequest.retryPolicy = DefaultRetryPolicy(45000, 0, 1f)
        queue.add(stringRequest)
    }

    // 날짜를 바꿀 때마다 그 날짜의 기록을 서버에서 통째로 다시 받아온다.
    // 화면에서 처리한 적 있는 기록(pendingSends) 중 이 날짜에 해당하는 것들을, 방금 서버에서
    // 받아온 스프레드시트의 실제 상태(serverData)와 하나씩 대조한다. 있어야 할 기록이 없거나
    // ('리셋'인데 아직 안 지워졌거나) 다르면, 그 자리에서 원래 보냈던 그대로 다시 전송한다.
    // 🔄 새로고침, 날짜 변경 조회, "시트 전송" 모두 이 대조를 거치게 되어 있다.
    private fun reconcilePendingSends(dateText: String, serverData: JSONArray?) {
        val targets = pendingSends.filter { it.dateKey == dateText }
        if (targets.isEmpty()) return

        val serverStatusBySlot = mutableMapOf<String, String>() // "학번|슬롯" -> status
        if (serverData != null) {
            for (i in 0 until serverData.length()) {
                val obj = serverData.optJSONObject(i) ?: continue
                val key = "${obj.optString("studentNum")}|${obj.optString("slotName")}"
                serverStatusBySlot[key] = obj.optString("status")
            }
        }

        var resentCount = 0
        targets.forEach { record ->
            val key = "${record.studentNum}|${record.slotName}"
            val serverStatus = serverStatusBySlot[key]
            val verified = if (record.status == "리셋") serverStatus == null else serverStatus == record.status

            if (verified) {
                pendingSends.removeAll { it.id == record.id }
            } else {
                resentCount++
                sendSingleLogToGoogleAsync(record.studentNum, record.slotName, record.status, record.dateKey, record.recordTime, record.inputType) { ok, _ ->
                    if (ok) pendingSends.removeAll { it.id == record.id }
                }
            }
        }

        if (resentCount > 0) {
            statusMessage.value = "🔁 시트에 빠져 있던 기록 ${resentCount}건을 다시 전송했습니다."
        }
    }

    private fun fetchAttendanceStatusForDate(dateText: String) {
        val queue = Volley.newRequestQueue(this)
        val requestUrl = "$googleSheetUrl?mode=GET_ATTENDANCE_BY_DATE&date=$dateText"

        val stringRequest = object : StringRequest(Request.Method.GET, requestUrl,
            { response ->
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.optString("result", "fail") == "success") {
                        val dataArray = jsonResponse.optJSONArray("data")
                        dailyStatusMap.clear()
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.optJSONObject(i) ?: continue
                                val sNum = obj.optString("studentNum")
                                val slotName = obj.optString("slotName")
                                val status = obj.optString("status")
                                val recordTime = obj.optString("recordTime").take(5)
                                val displayText = if (status == "출석" || status == "지각") "$status ($recordTime)" else status

                                val current = dailyStatusMap[sNum] ?: DailyStatus()
                                dailyStatusMap[sNum] = when (slotName) {
                                    "방과후" -> current.copy(afterSchool = displayText)
                                    "야간1" -> current.copy(night1 = displayText)
                                    "야간2" -> current.copy(night2 = displayText)
                                    else -> current
                                }
                            }
                        }
                        statusMessage.value = if (dailyStatusMap.isEmpty()) "✅ $dateText 기록 없음 (명부 로드 완료)" else "✅ $dateText 기록 ${dailyStatusMap.size}건 로드 완료!"
                        // 화면에서 처리했지만 시트 반영이 확인되지 않은 기록이 있으면, 방금 받아온
                        // 시트의 실제 상태와 대조해서 누락된 것만 다시 전송한다.
                        reconcilePendingSends(dateText, dataArray)
                    }
                } catch (e: Exception) {
                    statusMessage.value = "⚠️ 출결 기록 해석 오류"
                }
            },
            { statusMessage.value = "❌ 출결 기록 로드 실패 (서버 응답 지연)" }
        ) {
            override fun getHeaders(): MutableMap<String, String> {
                val headers = HashMap<String, String>()
                headers["User-Agent"] = "Mozilla/5.0"
                return headers
            }
        }
        stringRequest.retryPolicy = DefaultRetryPolicy(45000, 0, 1f)
        queue.add(stringRequest)
    }

    private fun fetchAttendanceLogsFromGoogle(date: String, className: String) {
        statusMessage.value = "🔍 $date [$className] 누적 로그 조회 중..."
        val queue = Volley.newRequestQueue(this)
        val requestUrl = "$googleSheetUrl?mode=GET_ATTENDANCE_LOGS&date=$date&className=${URLEncoder.encode(className, "UTF-8")}"

        val stringRequest = StringRequest(Request.Method.GET, requestUrl,
            { response ->
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.optString("result") == "success") {
                        val dataArray = jsonResponse.optJSONArray("data")
                        teacherConfirmList.clear()
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.optJSONObject(i) ?: continue
                                teacherConfirmList.add(
                                    TeacherLogItem(
                                        date = obj.optString("date"),
                                        studentNum = obj.optString("studentNum"),
                                        name = obj.optString("name"),
                                        className = obj.optString("className"),
                                        slotName = obj.optString("slotName"),
                                        status = obj.optString("status"),
                                        reason = mutableStateOf(obj.optString("reason", ""))
                                    )
                                )
                            }
                        }
                        statusMessage.value = "📊 $date $className 로그 조회 완료 (총 ${teacherConfirmList.size}건)"
                    }
                } catch (e: Exception) {
                    statusMessage.value = "⚠️ 결석 로그 해석 마찰 발생"
                }
            },
            { statusMessage.value = "❌ 누적기록 서버 통신 실패" }
        )
        stringRequest.retryPolicy = DefaultRetryPolicy(45000, 0, 1f)
        queue.add(stringRequest)
    }

    private fun updateAbsenceReasonOnGoogle(item: TeacherLogItem, newReason: String) {
        val body = JSONObject().apply {
            put("mode", "UPDATE_REASON")
            put("date", item.date)
            put("studentNum", item.studentNum)
            put("slotName", item.slotName)
            put("reason", newReason)
        }

        val conn = URL(googleSheetUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 45000
        conn.readTimeout = 45000
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

        OutputStreamWriter(conn.outputStream, "UTF-8").use {
            it.write(body.toString())
            it.flush()
        }

        val result = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        } else "fail_${conn.responseCode}"
    }

    private fun fetchMonthlyStats(month: String) {
        statsLoading.value = true
        statsError.value = ""
        statsData.value = null

        CoroutineScope(Dispatchers.Main).launch {
            val result: String = withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL("$googleSheetUrl?mode=GET_MONTHLY_STATS&month=$month")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 45000
                    conn.readTimeout = 45000
                    conn.setRequestProperty("Accept", "application/json")
                    conn.inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    "ERROR:${e.message}"
                }
            }

            statsLoading.value = false

            if (result.startsWith("ERROR:")) {
                statsError.value = "네트워크 오류: ${result.removePrefix("ERROR:")}"
                return@launch
            }

            val trimmed = result.trim()
            if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") || trimmed.startsWith("<")) {
                statsError.value = "서버가 JSON 대신 HTML을 반환했습니다. Apps Script doGet 배포 상태를 확인하세요."
                return@launch
            }

            try {
                val obj = JSONObject(trimmed)
                if (obj.has("error")) {
                    statsError.value = obj.getString("error")
                    return@launch
                }

                val slotsArr = obj.getJSONArray("slots")
                val slots = (0 until slotsArr.length()).map { slotsArr.getString(it) }

                fun parseStudents(arr: JSONArray): List<MonthlySlotEntry> {
                    return (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        val slArr = s.getJSONArray("slots")
                        MonthlySlotEntry(
                            studentNum = s.optString("studentNum"),
                            name = s.optString("name"),
                            className = s.optString("className"),
                            present = s.optInt("present"),
                            late = s.optInt("late"),
                            absent = s.optInt("absent"),
                            total = s.optInt("total"),
                            slots = (0 until slArr.length()).map { slArr.getString(it) }
                        )
                    }
                }

                statsData.value = MonthlyStats(
                    month = obj.optString("month"),
                    slots = slots,
                    students = parseStudents(obj.getJSONArray("students")),
                    top10 = parseStudents(obj.getJSONArray("top10")),
                    bottom10 = parseStudents(obj.getJSONArray("bottom10"))
                )
            } catch (e: Exception) {
                statsError.value = "데이터 파싱 오류: ${e.message}"
            }
        }
    }

    // NFC 태깅 / 수동 처리 모두 이 함수 하나를 거친다. 과거에는 전송 실패가 Log.e로만 남고
    // 화면에는 아무 표시도 없어서, 선생님은 전송이 됐는지 안 됐는지 알 수 없었다. 이제는
    // 실패하면 상태 메시지와 Toast로 반드시 알린다.
    private fun sendSingleLogToGoogleAsync(
        studentNum: String, slotName: String, status: String,
        dateKey: String, recordTime: String, inputType: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var ok = false
            var message = ""
            try {
                val body = JSONObject().apply {
                    put("mode", "SAVE_ATTENDANCE")
                    put("records", JSONArray().put(JSONObject().apply {
                        put("studentNum", studentNum)
                        put("slotName", slotName)
                        put("status", status)
                        put("date", dateKey)
                        put("recordTime", recordTime)
                        put("inputType", inputType)
                    }))
                }

                val conn = URL(googleSheetUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 45000
                conn.readTimeout = 45000
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                OutputStreamWriter(conn.outputStream, "UTF-8").use {
                    it.write(body.toString())
                    it.flush()
                }

                val responseText = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                } else "fail_${conn.responseCode}"

                val json = JSONObject(responseText)
                ok = json.optString("result", "fail") == "success"
                message = json.optString("message", responseText)
            } catch (e: Exception) {
                ok = false
                message = e.message ?: "알 수 없는 오류"
            }

            withContext(Dispatchers.Main) {
                if (ok) {
                    Log.d("NFC_SEND", "단건 전송 성공: $message")
                } else {
                    Log.e("NFC_SEND", "단건 전송 실패: $message")
                    statusMessage.value = "⚠️ 전송 실패: $message"
                    Toast.makeText(this@MainActivity, "전송 실패: $message", Toast.LENGTH_LONG).show()
                }
                onResult?.invoke(ok, message)
            }
        }
    }

    private fun sendAttendanceLogToGoogle() {
        val dateKey = selectedDate.value
        val now = Calendar.getInstance()
        val timeString = String.format("%02d:%02d:00", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val jsonRecords = JSONArray()

        masterStudentList.forEach { student ->
            val status = dailyStatusMap[student.studentNum] ?: return@forEach
            listOf("방과후" to status.afterSchool, "야간1" to status.night1, "야간2" to status.night2).forEach { (slot, raw) ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return@forEach
                val normalized = when {
                    trimmed.contains("출석") -> "출석"
                    trimmed.contains("지각") -> "지각"
                    trimmed == "결석" -> "결석"
                    trimmed == "학사" -> "학사"
                    else -> return@forEach
                }
                jsonRecords.put(JSONObject().apply {
                    put("studentNum", student.studentNum)
                    put("slotName", slot)
                    put("status", normalized)
                    put("date", dateKey)
                    put("recordTime", timeString)
                    put("inputType", "수동")
                })
            }
        }

        if (jsonRecords.length() == 0) {
            Toast.makeText(this, "실제 출결 처리된 학생이 없습니다.", Toast.LENGTH_LONG).show()
            return
        }

        statusMessage.value = "📤 $dateKey 출결 ${jsonRecords.length()}건 일괄 전송 중..."
        val mainBody = JSONObject()
        mainBody.put("mode", "SAVE_ATTENDANCE")
        mainBody.put("records", jsonRecords)

        CoroutineScope(Dispatchers.Main).launch {
            val result: String = withContext(Dispatchers.IO) {
                try {
                    val url = URL(googleSheetUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.doInput = true
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 45000
                    conn.readTimeout = 45000
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                    val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                    writer.write(mainBody.toString())
                    writer.flush()
                    writer.close()

                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    } else "fail_${conn.responseCode}"
                } catch (e: Exception) { "fail:${e.message}" }
            }
            try {
                val jsonResponse = JSONObject(result)
                if (jsonResponse.optString("result") == "success") {
                    statusMessage.value = "✅ 일괄 전송 성공: ${jsonRecords.length()}건 (${jsonResponse.optString("message")})"
                    Toast.makeText(this@MainActivity, "구글 시트에 출결 데이터가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    // 화면에 보이는 모든 상태를 방금 통째로 다시 보냈으므로, 이 날짜의 미확인
                    // 목록은 전부 해소된 것으로 본다.
                    pendingSends.removeAll { it.dateKey == dateKey }
                } else {
                    val msg = jsonResponse.optString("message", result)
                    statusMessage.value = "⚠️ 전송 실패: $msg"
                    Toast.makeText(this@MainActivity, "전송 실패: $msg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                statusMessage.value = "❌ 구글 서버 통신 마찰 발생: $result"
                Toast.makeText(this@MainActivity, "전송 실패 (응답 해석 오류)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchStudentListFromGoogle() {
        statusMessage.value = "학생 목록 불러오는 중..."
        val queue = Volley.newRequestQueue(this)
        val requestUrl = "$googleSheetUrl?mode=GETSTUDENTLIST"
        val stringRequest = object : StringRequest(Method.GET, requestUrl,
            { response ->
                try {
                    val json = JSONObject(response)
                    if (json.optString("result", "fail") == "success") {
                        val arr = json.optJSONArray("data")
                        studentManagementList.clear()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                studentManagementList.add(
                                    StudentItem(
                                        studentId = obj.optString("studentId", ""),
                                        name = obj.optString("name", ""),
                                        nfcId = obj.optString("nfcId", ""),
                                        className = obj.optString("className", "")
                                    )
                                )
                            }
                        }
                        statusMessage.value = "학생 ${studentManagementList.size}명 로드 완료"
                    } else {
                        statusMessage.value = "학생 목록 로드 실패"
                    }
                } catch (e: Exception) { statusMessage.value = "파싱 오류" }
            },
            { statusMessage.value = "네트워크 오류" }
        ) {
            override fun getHeaders(): MutableMap<String, String> {
                val h = HashMap<String, String>()
                h["User-Agent"] = "Mozilla/5.0"
                return h
            }
        }
        stringRequest.retryPolicy = DefaultRetryPolicy(45000, 0, 1f)
        queue.add(stringRequest)
    }

    private fun addStudentToGoogle(studentId: String, name: String, nfcId: String, className: String) {
        statusMessage.value = "$name 등록 중..."
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("mode", "ADD_STUDENT")
                        put("studentId", studentId)
                        put("name", name)
                        put("nfcId", nfcId)
                        put("className", className)
                    }

                    val conn = URL(googleSheetUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 45000
                    conn.readTimeout = 45000
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                    OutputStreamWriter(conn.outputStream, "UTF-8").use {
                        it.write(body.toString())
                        it.flush()
                    }

                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    } else "fail_${conn.responseCode}"
                } catch (e: Exception) { "fail" }
            }
            if (result.contains("success")) {
                statusMessage.value = "$name 등록 완료!"
                fetchStudentListFromGoogle()
            } else {
                statusMessage.value = "등록 실패. 다시 시도해주세요."
            }
        }
    }

    private fun updateStudentNfcToGoogle(studentId: String, nfcId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("mode", "UPDATE_NFC")
                        put("studentId", studentId)
                        put("nfcId", nfcId)
                    }

                    val conn = URL(googleSheetUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 45000
                    conn.readTimeout = 45000
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    OutputStreamWriter(conn.outputStream, "UTF-8").use {
                        it.write(body.toString())
                        it.flush()
                    }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK)
                        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    else "fail_${conn.responseCode}"
                } catch (e: Exception) { "fail" }
            }
            if (result.contains("success")) {
                statusMessage.value = "NFC 등록 완료"
                fetchStudentListFromGoogle()
            } else {
                statusMessage.value = "NFC 등록 실패"
            }
        }
    }

    private fun deleteStudentFromGoogle(studentId: String) {
        statusMessage.value = "삭제 중..."
        CoroutineScope(Dispatchers.Main).launch {
            val result: String = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("mode", "DELETE_STUDENT")
                        put("studentId", studentId)
                    }
                    val conn = URL(googleSheetUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 45000
                    conn.readTimeout = 45000
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    OutputStreamWriter(conn.outputStream, "UTF-8").use {
                        it.write(body.toString()); it.flush()
                    }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK)
                        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    else "fail_${conn.responseCode}"
                } catch (e: Exception) { "fail" }
            }
            if (result.contains("success")) {
                statusMessage.value = "삭제 완료"
                fetchStudentListFromGoogle()
            } else {
                statusMessage.value = "삭제 실패"
            }
        }
    }
}

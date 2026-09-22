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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ─────────────────────────────────────────────
// 출석 타임(방과후/야간1/야간2) 정의를 앱 전체에서 단 한 곳에서만 관리한다.
// (예전에는 이 시간대가 서버 3곳 + 앱 2곳에 따로 하드코딩돼 있어서 서로 어긋나는 문제가 있었음)
// NFC 태깅 = 이 창구 안에 들어온 실제 시각을 그대로 기록.
// 수동 체크 = 신청/명단 개념이 없어졌으므로 fixedStartTime(타임 시작시각)으로 고정 기록.
// ─────────────────────────────────────────────
data class PeriodDef(
    val slotName: String,
    val onTimeStartMin: Int,
    val onTimeEndMin: Int,
    val lateEndMin: Int,
    val fixedStartTime: String,
    val displayLabel: String
)

val PERIODS = listOf(
    PeriodDef("방과후", 17 * 60, 17 * 60 + 14, 18 * 60 + 30, "17:00:00", "1타임(방과후)"),
    PeriodDef("야간1", 19 * 60, 19 * 60 + 14, 20 * 60 + 10, "19:00:00", "2타임(야간1)"),
    PeriodDef("야간2", 20 * 60 + 20, 20 * 60 + 34, 21 * 60 + 30, "20:20:00", "3타임(야간2)")
)

fun resolveNfcPeriod(totalMinutes: Int): Pair<PeriodDef, String>? {
    for (p in PERIODS) {
        if (totalMinutes in p.onTimeStartMin..p.onTimeEndMin) return p to "출석"
        if (totalMinutes in (p.onTimeEndMin + 1)..p.lateEndMin) return p to "지각"
    }
    return null
}

// 학번 첫 자리 = 학년 (전자칠판 Board_Handlers.gs의 inferGradeFromStudentId_ 와 동일한 규칙)
fun studentGrade(studentId: String): String = studentId.firstOrNull()?.toString() ?: ""

data class StudentItem(
    val studentId: String,
    val name: String,
    val nfcId: String,
    val className: String
)

data class DailyRecord(
    val slotName: String,
    val status: String,
    val recordTime: String,
    val inputType: String
)

enum class SyncState { NONE, PENDING, SYNCED, FAILED }

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

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private var currentMenu = mutableStateOf("HOME")
    // 전자칠판(Board.html)과 동일하게 학년을 먼저 고르고, 그 안에서 반을 고르는 구조.
    private var selectedGrade = mutableStateOf("1")
    private var selectedClass = mutableStateOf("전체")
    private var selectedDate = mutableStateOf("")
    private var statusMessage = mutableStateOf("🔄 구글 시트 데이터 로딩을 대기하고 있습니다...")

    private var todayDateAndDayDisplay = ""

    // 학생 명부 (HOME/학생관리 탭 공용, NFC학생현황 시트 기준)
    private val studentList = mutableStateListOf<StudentItem>()

    // 선택된 날짜의 출결 기록 오버레이: key = "학번|타임"
    private val dailyStatusMap = mutableStateMapOf<String, DailyRecord>()

    // 이번 세션에서 시도한 전송의 동기화 상태(시각적 피드백용): key = "학번|타임"
    private val syncStateMap = mutableStateMapOf<String, SyncState>()

    private var managementSelectedGrade = mutableStateOf("1")
    private var managementSelectedClass = mutableStateOf(1)

    private var isWaitingNfcForAdd = mutableStateOf(false)
    private var pendingNewStudentData = mutableStateOf<Triple<String, String, String>?>(null)
    private var pendingNfcTagResult = mutableStateOf("")
    private var nfcEditTargetStudent = mutableStateOf<StudentItem?>(null)
    private var isWaitingNfcForEdit = mutableStateOf(false)

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

    private val googleSheetUrl = "https://script.google.com/macros/s/AKfycbwB6Y7B4-sf_uZGC4vZJzvy98OPmKGB5pPHE9Q2apcyMMZU6OjJqSSR_HCTyNi6IC2w/exec"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val calendar = Calendar.getInstance()
        todayDateAndDayDisplay = SimpleDateFormat("M월 d일 EEEE", Locale.KOREAN).format(calendar.time)
        selectedDate.value = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN).format(calendar.time)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fetchStudentList()

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

            val matchedStudent = studentList.find { student ->
                val cleanSheetHex = student.nfcId.replace(":", "").replace(" ", "").trim()
                cleanSheetHex.isNotEmpty() && cleanSheetHex.equals(rawPhoneHex, ignoreCase = true)
            }

            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)
            val minute = now.get(Calendar.MINUTE)
            val timeString = String.format("%02d:%02d", hour, minute)
            val todayText = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN).format(now.time)

            if (matchedStudent != null) {
                val periodResult = resolveNfcPeriod(hour * 60 + minute)

                if (periodResult != null) {
                    val (period, status) = periodResult
                    statusMessage.value = "🎯 [NFC 태깅] ${matchedStudent.className} ${matchedStudent.name} $status 인정!"
                    Toast.makeText(this, "${matchedStudent.name} 학생 자습 참여 인정", Toast.LENGTH_SHORT).show()

                    if (isStudentMode.value) {
                        studentModeResult.value = StudentAttendanceResult(
                            name = matchedStudent.name,
                            className = matchedStudent.className,
                            slotName = period.slotName,
                            timeString = timeString,
                            status = status
                        )
                        lastTagTime.value = System.currentTimeMillis()
                    }

                    sendAttendanceRecord(
                        student = matchedStudent,
                        slotName = period.slotName,
                        status = status,
                        date = todayText,
                        recordTime = "$timeString:00",
                        inputType = "NFC"
                    )
                } else {
                    Toast.makeText(this, "${matchedStudent.name}: 자습 교시 운영 외 시간대 태깅입니다.", Toast.LENGTH_LONG).show()
                    if (isStudentMode.value) {
                        studentModeResult.value = StudentAttendanceResult(
                            name = matchedStudent.name,
                            className = matchedStudent.className,
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
                        message = "관리자에게 NFC 등록을 요청하세요."
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
                selectedDate.value = "$year-$formattedMonth-$formattedDay"
                fetchAttendanceForDate(selectedDate.value)
            }
        }
        val calendar = Calendar.getInstance()
        val datePickerDialog = DatePickerDialog(
            context, dateSetListener,
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
        )

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
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = { datePickerDialog.show() }, border = BorderStroke(1.dp, Color(0xFF0984E3))) {
                    Text(text = "📅 ${selectedDate.value}", fontWeight = FontWeight.Bold, color = Color(0xFF0984E3))
                }

                Button(
                    onClick = { fetchStudentList() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF74B9FF)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Text("🔄 새로고침", fontSize = 12.sp, color = Color.White)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp)
            ) {
                listOf("1", "2", "3").forEach { grade ->
                    val isSelected = selectedGrade.value == grade
                    Box(
                        modifier = Modifier
                            .clickable { selectedGrade.value = grade }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${grade}학년",
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF0984E3) else Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp)
            ) {
                val classList = listOf("전체", "1반", "2반", "3반", "4반", "5반", "6반", "7반", "8반", "9반")
                classList.forEach { cls ->
                    val isSelected = selectedClass.value == cls
                    Box(
                        modifier = Modifier
                            .clickable { selectedClass.value = cls }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cls,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF0984E3) else Color.Gray,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            val filteredStudents = studentList.filter {
                studentGrade(it.studentId) == selectedGrade.value &&
                    (selectedClass.value == "전체" || it.className == selectedClass.value)
            }.sortedBy { it.studentId }

            Text(
                text = "📊 ${selectedDate.value} ${selectedGrade.value}학년 ${selectedClass.value} 명단 (총 ${filteredStudents.size}명)",
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                color = Color.Gray
            )

            if (filteredStudents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "등록된 학생이 없습니다.\n학생관리 탭에서 먼저 등록해주세요.".replace("\\n", "\n"),
                        color = Color.Gray, textAlign = TextAlign.Center, fontSize = 14.sp
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
                                Column(modifier = Modifier.weight(1.2f)) {
                                    Text(student.studentId, fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(student.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3436))
                                }

                                Row(modifier = Modifier.weight(2.8f), horizontalArrangement = Arrangement.End) {
                                    PERIODS.forEachIndexed { idx, period ->
                                        val key = "${student.studentId}|${period.slotName}"
                                        val record = dailyStatusMap[key]
                                        val sync = syncStateMap[key] ?: SyncState.NONE
                                        DropdownTimeBadge(
                                            slotName = period.slotName,
                                            status = record?.status ?: "",
                                            syncState = sync
                                        ) { selectedStatus ->
                                            updateStudentStatus(student, period, selectedStatus)
                                        }
                                        if (idx != PERIODS.lastIndex) Spacer(modifier = Modifier.width(4.dp))
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
    fun DropdownTimeBadge(slotName: String, status: String, syncState: SyncState, onStatusSelected: (String) -> Unit) {
        val cleanStatus = status.trim()
        var isMenuExpanded by remember { mutableStateOf(false) }

        val isCheckedIn = cleanStatus == "출석"
        val isLate = cleanStatus == "지각"
        val isAbsent = cleanStatus == "결석"
        val isAcademic = cleanStatus == "학사"
        val isNotRecorded = cleanStatus.isEmpty()

        val bgContainerColor = when {
            isCheckedIn -> Color(0xFF2ED573) // 초록 (출석)
            isLate -> Color(0xFFFFA502)      // 주황 (지각)
            isAbsent -> Color(0xFFEA2027)    // 빨강 (결석)
            isAcademic -> Color(0xFF10AC84)  // 청록 (학사)
            else -> Color(0xFFF1F2F6).copy(alpha = 0.6f)
        }

        val contentTextColor = if (isNotRecorded) Color(0xFFA4B0BE) else Color.White
        val displayText = if (isNotRecorded) "미기록" else cleanStatus

        // 전송 상태 시각 피드백: 전송중(깜빡임) / 전송완료(파란 테두리) / 전송실패(빨간 테두리)
        val infiniteTransition = rememberInfiniteTransition(label = "sync_blink")
        val blinkAlpha by infiniteTransition.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse),
            label = "blink_alpha"
        )

        val borderColor = when (syncState) {
            SyncState.PENDING -> Color(0xFFFFD100).copy(alpha = blinkAlpha)
            SyncState.SYNCED -> Color(0xFF1A73E8)
            SyncState.FAILED -> Color(0xFFD63031)
            SyncState.NONE -> Color.Transparent
        }
        val borderWidth = if (syncState == SyncState.NONE) 0.dp else 2.dp

        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgContainerColor)
                    .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(6.dp))
                    .clickable { isMenuExpanded = true }
                    .padding(vertical = 6.dp)
            ) {
                Text(text = slotName, fontSize = 10.sp, color = if (!isNotRecorded) Color.White.copy(alpha = 0.85f) else Color.Gray)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = displayText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = contentTextColor, textAlign = TextAlign.Center)
                when (syncState) {
                    SyncState.PENDING -> Text("전송중…", fontSize = 7.sp, color = Color.White.copy(alpha = blinkAlpha))
                    SyncState.SYNCED -> Text("✓ 전송됨", fontSize = 7.sp, color = Color.White.copy(alpha = 0.9f))
                    SyncState.FAILED -> Text("전송실패", fontSize = 7.sp, color = Color.Yellow)
                    SyncState.NONE -> {}
                }
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
                    text = { Text("🔄 기록 삭제", fontSize = 12.sp, color = Color.Gray) },
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
        val periodResult = resolveNfcPeriod(totalMins)
        val currentSlot = if (periodResult != null) {
            val (period, status) = periodResult
            "${period.displayLabel} $status"
        } else {
            "운영 시간 외"
        }
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
                    else -> Color(0xFFD63031)
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
                                else -> "❌"
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
                            else -> "운영 시간 외 태깅"
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
            compareBy { it.replace("반", "").toIntOrNull() ?: 99 }
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
                                        text = slot.split("|")[1].replace("타임", ""),
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
                                    "학사" -> Color(0xFF74B9FF)
                                    else -> Color(0xFFDFE6E9)
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
                                    else if (status == "학사") Text("학", fontSize = 7.sp, color = Color.White)
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
                    HorizontalDivider(color = Color(0xFFF1F2F6), thickness = 0.5.dp)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    @Composable
    fun RankingContent(students: List<MonthlySlotEntry>, slots: List<String>, isTop: Boolean) {
        val accentColor = if (isTop) Color(0xFF0984E3) else Color(0xFFD63031)
        val titleText = if (isTop) "🏆 참여 상위 10명" else "⚠️ 참여 하위 10명"

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
                                                else -> Color(0xFFDFE6E9)
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
                                                    else -> Color(0xFFDFE6E9)
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

        val filteredStudents = studentList
            .filter { studentGrade(it.studentId) == managementSelectedGrade.value && it.className == "${managementSelectedClass.value}반" }
            .sortedBy { it.studentId }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("학생 관리", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0984E3))
                Button(
                    onClick = { fetchStudentList() },
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
                listOf("1", "2", "3").forEach { grade ->
                    val isSelected = managementSelectedGrade.value == grade
                    Box(
                        modifier = Modifier
                            .clickable { managementSelectedGrade.value = grade }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${grade}학년",
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF0984E3) else Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
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
                            text = "${managementSelectedGrade.value}-$cls",
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
                    text = "${managementSelectedGrade.value}-${managementSelectedClass.value}반  총 ${filteredStudents.size}명",
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
            val target = studentList.find { it.studentId == targetId }
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

    // 수동 체크: 신청/명단 개념이 없으므로 타임의 "시작시간"으로 고정 기록한다.
    // (조금 늦게 처리하거나 뒤늦게 체크해도 해당 차시에 정상 참여했다는 기록이 남도록)
    private fun updateStudentStatus(student: StudentItem, period: PeriodDef, action: String) {
        val status = when (action) {
            "출석", "지각", "결석", "학사", "리셋" -> action
            else -> return
        }
        statusMessage.value = "🎯 [수동 변경] ${student.name} -> ${period.slotName} [$action] 반영"
        sendAttendanceRecord(
            student = student,
            slotName = period.slotName,
            status = status,
            date = selectedDate.value,
            recordTime = period.fixedStartTime,
            inputType = "수동"
        )
    }

    // NFC/수동 체크 모두 이 함수를 거쳐 전송하며, 전송 상태를 syncStateMap에 실시간 반영한다.
    private fun sendAttendanceRecord(
        student: StudentItem,
        slotName: String,
        status: String,
        date: String,
        recordTime: String,
        inputType: String
    ) {
        val key = "${student.studentId}|$slotName"
        syncStateMap[key] = SyncState.PENDING

        CoroutineScope(Dispatchers.IO).launch {
            val success = try {
                val body = JSONObject().apply {
                    put("mode", "SAVE_ATTENDANCE")
                    put("records", JSONArray().put(JSONObject().apply {
                        put("studentNum", student.studentId)
                        put("name", student.name)
                        put("className", student.className)
                        put("slotName", slotName)
                        put("status", status)
                        put("date", date)
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

                val result = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                } else "fail_${conn.responseCode}"

                Log.d("NFC_SEND", "전송 결과: $result")
                result.contains("success")
            } catch (e: Exception) {
                Log.e("NFC_SEND", "전송 오류: ${e.message}")
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    syncStateMap[key] = SyncState.SYNCED
                    if (date == selectedDate.value) {
                        if (status == "리셋") {
                            dailyStatusMap.remove(key)
                        } else {
                            dailyStatusMap[key] = DailyRecord(slotName, status, recordTime, inputType)
                        }
                    }
                } else {
                    syncStateMap[key] = SyncState.FAILED
                }
            }
        }
    }

    private fun fetchStudentList() {
        statusMessage.value = "🔄 학생 명부 불러오는 중..."
        val queue = Volley.newRequestQueue(this)
        val requestUrl = "$googleSheetUrl?mode=GET_MASTER"

        val stringRequest = object : StringRequest(Request.Method.GET, requestUrl,
            { response ->
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.optString("result", "fail") == "success") {
                        val dataArray = jsonResponse.optJSONArray("data")
                        if (dataArray != null) {
                            studentList.clear()
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.optJSONObject(i) ?: continue
                                studentList.add(
                                    StudentItem(
                                        studentId = obj.optString("studentId", ""),
                                        name = obj.optString("name", ""),
                                        nfcId = obj.optString("nfcId", ""),
                                        className = obj.optString("className", "")
                                    )
                                )
                            }
                            statusMessage.value = "✅ 학생 ${studentList.size}명 명부 로드 완료"
                            fetchAttendanceForDate(selectedDate.value)
                        }
                    } else {
                        statusMessage.value = "⚠️ 명부 로드 실패"
                    }
                } catch (e: Exception) {
                    statusMessage.value = "⚠️ 명부 데이터 해석 오류"
                }
            },
            { statusMessage.value = "❌ 구글 서버 연결 실패" }
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

    private fun fetchAttendanceForDate(date: String) {
        statusMessage.value = "🔄 $date 출결 기록 불러오는 중..."
        val queue = Volley.newRequestQueue(this)
        val requestUrl = "$googleSheetUrl?mode=GET_ATTENDANCE_BY_DATE&date=$date"

        val stringRequest = object : StringRequest(Request.Method.GET, requestUrl,
            { response ->
                try {
                    val jsonResponse = JSONObject(response)
                    if (jsonResponse.optString("result", "fail") == "success") {
                        val dataArray = jsonResponse.optJSONArray("data")
                        dailyStatusMap.clear()
                        syncStateMap.clear()
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.optJSONObject(i) ?: continue
                                val sNum = obj.optString("studentNum")
                                val slot = obj.optString("slotName")
                                dailyStatusMap["$sNum|$slot"] = DailyRecord(
                                    slotName = slot,
                                    status = obj.optString("status"),
                                    recordTime = obj.optString("recordTime"),
                                    inputType = obj.optString("inputType")
                                )
                            }
                        }
                        statusMessage.value = "✅ $date 출결 기록 (${dailyStatusMap.size}건) 로드 완료"
                    }
                } catch (e: Exception) {
                    statusMessage.value = "⚠️ 출결 기록 해석 오류"
                }
            },
            { statusMessage.value = "❌ 출결 기록 로드 실패" }
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

    private fun fetchMonthlyStats(month: String) {
        statsLoading.value = true
        statsError.value = ""
        statsData.value = null

        CoroutineScope(Dispatchers.Main).launch {
            val result: String = withContext(Dispatchers.IO) {
                try {
                    val url = URL("$googleSheetUrl?mode=GET_MONTHLY_STATS&month=$month")
                    val conn = url.openConnection() as HttpURLConnection
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
                fetchStudentList()
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
                fetchStudentList()
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
                fetchStudentList()
            } else {
                statusMessage.value = "삭제 실패"
            }
        }
    }
}

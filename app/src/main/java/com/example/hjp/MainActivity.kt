package com.example.hjp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hjp.ui.theme.HJPTheme

class MainActivity : ComponentActivity() {
    private lateinit var agentViewModel: AgentViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HjpApplication).container
        agentViewModel = ViewModelProvider(this, AgentViewModel.factory(container))[AgentViewModel::class.java]
        setContent {
            val state by agentViewModel.state.collectAsStateWithLifecycle()
            HJPTheme {
                AgentScreen(
                    state = state,
                    onSend = agentViewModel::send,
                    onCancel = agentViewModel::cancel,
                    onResetSession = agentViewModel::resetSession,
                    onConfirm = { agentViewModel.answerConfirmation(true) },
                    onReject = { agentViewModel.answerConfirmation(false) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::agentViewModel.isInitialized) agentViewModel.refreshReadiness()
    }
}

@Composable
private fun AgentScreen(
    state: AgentUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onResetSession: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("HJP Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("온디바이스 Single ReAct · 검색/조회/외부 작성 연동", style = MaterialTheme.typography.bodySmall)

            if (!state.modelReady) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("모델 파일이 필요합니다.", fontWeight = FontWeight.SemiBold)
                        Text(state.modelPath, style = MaterialTheme.typography.bodySmall)
                        Text(".litertlm 모델을 위 경로에 배치한 뒤 앱을 다시 열어 주세요.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item { Text("예: \"판교에서 만난 AI 개발자 찾아줘\"", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(state.messages) { message -> MessageCard(message) }
                state.status?.let { status -> item { Text(status, style = MaterialTheme.typography.labelMedium) } }
            }

            state.confirmationPrompt?.let { prompt ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(prompt, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("실행") }
                            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("취소") }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                enabled = !state.busy && state.modelReady,
                label = { Text("요청") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSend(input); input = "" },
                    enabled = input.isNotBlank() && !state.busy && state.modelReady,
                    modifier = Modifier.weight(1f),
                ) { Text("보내기") }
                if (state.busy) {
                    OutlinedButton(onClick = onCancel) { Text("취소") }
                } else {
                    OutlinedButton(
                        onClick = {
                            input = ""
                            onResetSession()
                        },
                    ) { Text("새 대화") }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) { Text(message.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}

package com.anatdx.nemuri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anatdx.nemuri.ui.NemuriTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XposedServiceStatus.start()
        enableEdgeToEdge()
        setContent {
            NemuriTheme {
                NemuriApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NemuriApp() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "Nemuri", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Modern LSPosed freezer module",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ModuleStatusCard()
            }
            items(FoundationItem.entries) { item ->
                FoundationCard(item = item)
            }
        }
    }
}

@Composable
private fun ModuleStatusCard() {
    val status = XposedServiceStatus.state.value
    val containerColor = if (status.active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (status.active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (status.active) "Xposed framework active" else "Xposed framework inactive",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (status.active) {
                    "Connected to ${status.frameworkLabel}. Nemuri can query module scope and framework capabilities through libxposed service."
                } else {
                    "Open LSPosed, enable Nemuri, keep system framework in scope, then relaunch Nemuri after the framework reloads."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (status.active) "active" else "inactive") })
                AssistChip(onClick = {}, label = { Text("API ${status.apiLabel}") })
                AssistChip(onClick = {}, label = { Text("${status.scope.size} scope") })
            }
        }
    }
}

@Composable
private fun FoundationCard(item: FoundationItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(item.iconRes),
                contentDescription = null,
                tint = item.tint,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private enum class FoundationItem(
    val title: String,
    val description: String,
    val iconRes: Int,
    val tint: Color,
) {
    Framework(
        title = "Framework observer",
        description = "A system-server entry point is ready for process, app-state, thread and Binder hooks.",
        iconRes = R.drawable.ic_framework,
        tint = Color(0xFF2D6A4F),
    ),
    Policy(
        title = "Freeze policy",
        description = "Policy storage and actual tombstoning behavior are intentionally left as the next layer.",
        iconRes = R.drawable.ic_policy,
        tint = Color(0xFF835700),
    ),
    Kernel(
        title = "Re-Kernel later",
        description = "Kernel-side integration stays out of the critical path until core freezer behavior is proven.",
        iconRes = R.drawable.ic_kernel,
        tint = Color(0xFF4E5F9B),
    ),
}

@Preview
@Composable
private fun NemuriAppPreview() {
    NemuriTheme {
        NemuriApp()
    }
}

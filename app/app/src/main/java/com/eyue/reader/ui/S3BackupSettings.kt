package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.cloud.CloudCredentials
import com.eyue.reader.data.cloud.S3Location
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun S3AccountDialog(saved: CloudCredentials?, initialName: String, onSave: (CloudCredentials, String) -> Unit,
    onTest: (suspend (CloudCredentials) -> String)?, onClose: () -> Unit, initialCos: Boolean = false) {
    var name by remember { mutableStateOf(initialName) }
    var endpoint by remember { mutableStateOf(saved?.serverUrl.orEmpty()) }
    var bucket by remember { mutableStateOf(saved?.s3?.bucket.orEmpty()) }
    var region by remember { mutableStateOf(saved?.s3?.region ?: if (initialCos) "" else "us-east-1") }
    var prefix by remember { mutableStateOf(saved?.s3?.prefix ?: "ERead") }
    var access by remember { mutableStateOf(saved?.account.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var pathStyle by remember { mutableStateOf(saved?.s3?.pathStyle ?: true) }
    val cos = saved?.s3?.cos ?: initialCos
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = runCatching {
        val location = S3Location(bucket.trim(), region.trim(), prefix.trim().trim('/'), if (cos) false else pathStyle, cos)
        val password = secret.ifBlank {
            require(saved != null && saved.account == access.trim() && saved.serverUrl ==
                com.eyue.reader.data.cloud.normalizeWebDavServer(endpoint)) {
                if (saved == null) "请填写 Secret Key" else "地址或 Access Key 已更改，请重新填写 Secret Key"
            }
            saved.password
        }
        CloudCredentials(access.trim(), password, endpoint, location)
    }
    LaunchedEffect(endpoint, bucket, region, prefix, access, secret, pathStyle, cos) { result = null }
    AlertDialog(onDismissRequest = { if (!testing) onClose() }, title = { Text(if (cos) "腾讯云 COS 备份配置" else "S3 备份配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fun fieldModifier(tag: String) = Modifier.fillMaxWidth().testTag("s3_$tag")
                OutlinedTextField(name, { name = it }, label = { Text("配置名称") }, enabled = !testing, modifier = fieldModifier("name"))
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint") }, placeholder = { Text("https://s3.example.com") },
                    supportingText = { Text("填写服务地址，不要包含 Bucket 桶名") },
                    singleLine = true, enabled = !testing, modifier = fieldModifier("endpoint"))
                OutlinedTextField(bucket, { bucket = it }, label = { Text("Bucket 存储桶") }, singleLine = true, enabled = !testing, modifier = fieldModifier("bucket"))
                OutlinedTextField(region, { region = it }, label = { Text("Region 区域") }, singleLine = true, enabled = !testing, modifier = fieldModifier("region"))
                OutlinedTextField(prefix, { prefix = it }, label = { Text("备份路径前缀") }, singleLine = true, enabled = !testing, modifier = fieldModifier("prefix"))
                OutlinedTextField(access, { access = it }, label = { Text("Access Key") }, singleLine = true, enabled = !testing, modifier = fieldModifier("access"))
                OutlinedTextField(secret, { secret = it }, label = { Text("Secret Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !testing, modifier = fieldModifier("secret"))
                if (saved != null) Text("密钥留空沿用此地址和账户已保存的密钥", style = MaterialTheme.typography.bodySmall)
                if (endpoint.isNotBlank() && bucket.isNotBlank() && access.isNotBlank()) {
                    draft.exceptionOrNull()?.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("s3_validation_error"))
                    }
                }
                if (!cos) Row { Checkbox(pathStyle, { pathStyle = it }, enabled = !testing, modifier = Modifier.testTag("s3_path_style"))
                    Text("路径式地址（MinIO 等兼容服务）") }
                if (cos) Text("Endpoint 示例：https://cos.ap-nanjing.myqcloud.com。使用从未开启版本控制的专用桶，不要对备份目录设置自动删除规则。COS 使用前缀内的 cos-v1 独立目录，保留记录版本以保护多设备同步。",
                    style = MaterialTheme.typography.bodySmall)
                Text("需要列举、读取、写入和删除权限。连接测试会创建并清理临时文件。使用现有存储桶，区域按服务商填写。",
                    style = MaterialTheme.typography.bodySmall)
                if (endpoint.startsWith("http://")) Text("HTTP 会明文传输书籍和记录，建议使用 HTTPS。", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = draft.isSuccess && !testing && onTest != null, modifier = Modifier.testTag("s3_test"),
                    onClick = {
                        val credentials = draft.getOrThrow()
                        testing = true; result = null
                        scope.launch {
                            try { result = onTest?.invoke(credentials) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { result = failure.message ?: "S3 连接失败" }
                            finally { testing = false }
                        }
                    }) { Text(if (testing) "正在测试…" else "测试连接") }
                result?.let { SelectionContainer { Text(it, modifier = Modifier.testTag("s3_test_result")) } }
            }
        },
        confirmButton = { TextButton(enabled = draft.isSuccess && name.isNotBlank() && !testing,
            modifier = Modifier.testTag("s3_save"),
            onClick = { onSave(draft.getOrThrow(), name.trim()); onClose() }) { Text("保存配置") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !testing) { Text("取消") } })
}

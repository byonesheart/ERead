package com.eyue.reader.data.cloud

internal fun resolveCloudConnectionDraft(account: String, password: String, server: String,
    saved: CloudCredentials?): CloudCredentials {
    val address = normalizeWebDavServer(server)
    val user = account.trim()
    val secret = password.ifBlank {
        require(saved != null && saved.account == user && saved.serverUrl == address) { "更换服务器或账户时请填写密码" }
        saved.password
    }
    return CloudCredentials(user, secret, address)
}

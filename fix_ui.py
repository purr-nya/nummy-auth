import re

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

# Modify WatchFaceInterface parameters
old_watchface = "fun WatchFaceInterface(\n    accounts: List<OtpAccount>,\n    currentTime: Long,\n    lockEnabled: Boolean,\n    onToggleLock: (Boolean) -> Unit,\n    onScan: () -> Unit,\n    onManual: () -> Unit,\n    onSync: () -> Unit,\n    onDelete: (OtpAccount) -> Unit\n) {"
content = content.replace(old_watchface, old_watchface) # It already has onManual and onSync

# Modify WatchFaceInterface body where WatchBottomBar is called
old_call = "WatchBottomBar(pagerState.currentPage, accounts.size, onScan)"
new_call = "WatchBottomBar(pagerState.currentPage, accounts.size, onScan, onManual, onSync)"
content = content.replace(old_call, new_call)

# Modify WatchBottomBar
old_bottom_bar = """@Composable
fun WatchBottomBar(currentPage: Int, totalPages: Int, onScan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (totalPages > 1) {
            Text(
                "${currentPage + 1}/${totalPages}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.DarkGray,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        IconButton(
            onClick = onScan,
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(Icons.Default.Add, null, tint = Color.Black, modifier = Modifier.size(36.dp))
        }
    }
}"""

new_bottom_bar = """@Composable
fun WatchBottomBar(currentPage: Int, totalPages: Int, onScan: () -> Unit, onManual: () -> Unit, onSync: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (totalPages > 1) {
            Text(
                "${currentPage + 1}/${totalPages}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.DarkGray,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onSync, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Sync, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            }
            IconButton(
                onClick = onScan,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(Icons.Default.QrCodeScanner, null, tint = Color.Black, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onManual, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            }
        }
    }
}"""

content = content.replace(old_bottom_bar, new_bottom_bar)

# Modify QrCodeScannerView to hide the built-in view finder
content = content.replace('this.setStatusText("")', 'this.setStatusText("")\n            try { this.findViewById<android.view.View>(com.google.zxing.client.android.R.id.zxing_viewfinder_view)?.visibility = android.view.View.GONE } catch (e: Exception) {}')

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)


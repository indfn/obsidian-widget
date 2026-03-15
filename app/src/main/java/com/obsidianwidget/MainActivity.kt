package com.obsidianwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager
    private lateinit var vaultPathText: TextView
    private lateinit var statusText: TextView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { onVaultSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vaultManager = VaultManager(this)
        vaultPathText = findViewById(R.id.vault_path_text)
        statusText = findViewById(R.id.status_text)

        findViewById<Button>(R.id.btn_select_vault).setOnClickListener {
            folderPicker.launch(null)
        }

        loadCurrentSettings()
    }

    private fun loadCurrentSettings() {
        if (vaultManager.isVaultConfigured) {
            vaultPathText.text = getString(R.string.vault_selected, vaultManager.vaultName ?: "Selected")
            statusText.text = "\u2705 Vault configured. Add the widget to your home screen!\n\nTap the \u2699 icon on each widget to configure it."
        } else {
            statusText.text = "Select your Obsidian vault folder to get started."
        }
    }

    private fun onVaultSelected(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        vaultManager.vaultUri = uri
        vaultManager.vaultName = uri.lastPathSegment?.substringAfterLast(':') ?: "Vault"

        vaultPathText.text = getString(R.string.vault_selected, vaultManager.vaultName)
        statusText.text = "\u2705 Vault configured. Add the widget to your home screen!\n\nTap the \u2699 icon on each widget to configure it."

        ObsidianWidgetProvider.updateAllWidgets(this)
    }
}

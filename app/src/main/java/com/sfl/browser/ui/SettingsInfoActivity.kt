package com.sfl.browser.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.sfl.browser.R
import com.sfl.browser.data.Prefs

class SettingsInfoActivity : PaletteActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_info)
        SystemBarInsets.apply(window, findViewById(R.id.settingsInfoRoot))

        val page = intent.getIntExtra(EXTRA_PAGE, PAGE_ABOUT_DEV)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(when (page) {
            PAGE_CREDITS -> R.string.settings_credits
            PAGE_TUTORIAL -> R.string.settings_tutorial
            else -> R.string.settings_about_dev
        })
        toolbar.setNavigationOnClickListener { finish() }
        val content = findViewById<LinearLayout>(R.id.settingsInfoContent)
        layoutInflater.inflate(when (page) {
            PAGE_CREDITS -> R.layout.settings_credits
            PAGE_TUTORIAL -> R.layout.settings_tutorial
            else -> R.layout.settings_about_dev
        }, content)

        when (page) {
            PAGE_CREDITS -> {
                bindFarmLink(R.id.andreiFarm, ANDREI_FARM)
                bindLink(R.id.creditsSflWorld, "https://sfl.world/")
                bindLink(R.id.creditsSflWiki, "https://wiki.sfl.world/")
                bindFarmLink(R.id.kohirabbitFarm, "https://sunflower-land.com/play/#/visit/61992")
                bindLink(R.id.kohirabbitPortfolio, "https://kohirabbit.carrd.co/")
                bindFarmLink(R.id.eliasFarm, "https://sunflower-land.com/play/#/visit/128727")
                bindFarmLink(R.id.maschsFarm, "https://sunflower-land.com/play/#/visit/137396")
            }
            PAGE_TUTORIAL -> {
                bindLink(R.id.tutorialDiscord, DISCORD_SUPPORT)
                bindLink(R.id.tutorialGithub, GITHUB_REPO)
            }
            else -> {
                bindFarmLink(R.id.devFarm, "https://sunflower-land.com/play/#/visit/1128976301583508")
                bindLink(R.id.devPlayStore, "https://play.google.com/store/apps/details?id=com.sfl.browser")
                findViewById<Button>(R.id.copyDonationAddress).setOnClickListener {
                    val address = getString(R.string.settings_dev_donation_address)
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText(getString(R.string.settings_dev_donation_heading), address),
                    )
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(this, R.string.settings_dev_address_copied, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        NativeFont.apply(content)
        toolbar.post { NativeFont.apply(toolbar) }
    }

    private fun bindFarmLink(button: Int, url: String) {
        findViewById<Button>(button).setOnClickListener {
            if (Prefs.notificationsOnly(this)) {
                NotificationDestination.openFarmLink(this, url)
            } else {
                startActivity(MainActivity.viewInMainTab(this, url))
            }
        }
    }

    private fun bindLink(button: Int, url: String) {
        findViewById<Button>(button).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        const val PAGE_ABOUT_DEV = 0
        const val PAGE_CREDITS = 1
        const val PAGE_TUTORIAL = 2
        private const val ANDREI_FARM = "https://sunflower-land.com/play/#/visit/114779"
        const val GITHUB_REPO = "https://github.com/ispankzombiez/SFL-Browser"
        const val PRIVACY_PAGE = "https://ispankzombiez.github.io/SFL-Browser/"
        private const val DISCORD_SUPPORT =
            "https://discord.com/channels/880987707214544966/1314031342182338651"

        fun intent(context: Context, page: Int): Intent =
            Intent(context, SettingsInfoActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}

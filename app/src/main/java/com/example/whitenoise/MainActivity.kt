package com.example.whitenoise

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.whitenoise.WhiteNoiseService.NoiseType
import com.example.whitenoise.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isPlaying = false
    private var volume = 1.0f

    // Exclusive types (White/Pink/Brown) — only one can be active at a time, cannot blend
    private var exclusiveType: NoiseType? = null

    // Blendable types (Ocean/Rain/Storm/Fan/Fire) — up to 2 simultaneously
    private val blendableTypes = LinkedHashSet<NoiseType>()

    private val prefs by lazy { getSharedPreferences("whitenoise_prefs", MODE_PRIVATE) }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
        private const val PREF_VOLUME = "volume"
        private const val PREF_NOISE_TYPE_1 = "noise_type_1"
        private const val PREF_NOISE_TYPE_2 = "noise_type_2"

        private val EXCLUSIVE_TYPES = setOf(NoiseType.WHITE, NoiseType.PINK, NoiseType.BROWN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore saved preferences
        volume = prefs.getFloat(PREF_VOLUME, 1.0f)
        val type1str = prefs.getString(PREF_NOISE_TYPE_1, NoiseType.WHITE.name)!!
        val type2str = prefs.getString(PREF_NOISE_TYPE_2, "NONE")

        val type1 = NoiseType.valueOf(type1str)
        val type2 = if (type2str == "NONE") null else NoiseType.valueOf(type2str!!)

        if (type1 in EXCLUSIVE_TYPES) {
            exclusiveType = type1
        } else {
            blendableTypes.add(type1)
            if (type2 != null && type2 !in EXCLUSIVE_TYPES) blendableTypes.add(type2)
        }

        binding.seekBarVolume.max = 100
        binding.seekBarVolume.progress = (volume * 100).toInt()

        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volume = progress / 100f
                prefs.edit().putFloat(PREF_VOLUME, volume).apply()
                if (isPlaying) sendServiceIntent(WhiteNoiseService.ACTION_START)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Wire up exclusive buttons
        binding.btnWhite.setOnClickListener { onExclusiveClicked(NoiseType.WHITE, binding.btnWhite) }
        binding.btnPink.setOnClickListener  { onExclusiveClicked(NoiseType.PINK,  binding.btnPink) }
        binding.btnBrown.setOnClickListener { onExclusiveClicked(NoiseType.BROWN, binding.btnBrown) }

        // Wire up blendable buttons
        binding.btnOcean.setOnClickListener { onBlendableClicked(NoiseType.OCEAN, binding.btnOcean) }
        binding.btnRain.setOnClickListener  { onBlendableClicked(NoiseType.RAIN,  binding.btnRain) }
binding.btnFan.setOnClickListener   { onBlendableClicked(NoiseType.FAN,   binding.btnFan) }
        binding.btnFire.setOnClickListener  { onBlendableClicked(NoiseType.FIRE,  binding.btnFire) }

        // Restore button checked states
        updateAllButtonStates()

        binding.btnPlayStop.setOnClickListener {
            if (isPlaying) stopWhiteNoise() else requestNotificationPermissionAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        isPlaying = WhiteNoiseService.isRunning
        binding.btnPlayStop.text = getString(if (isPlaying) R.string.stop else R.string.play)
        binding.statusText.text = getString(if (isPlaying) R.string.status_playing else R.string.status_stopped)
    }

    private fun onExclusiveClicked(type: NoiseType, button: MaterialButton) {
        if (exclusiveType == type) {
            // Clicking the only active sound — deselect and stop
            exclusiveType = null
            button.isChecked = false
            if (isPlaying) stopWhiteNoise()
            saveTypePrefs()
        } else {
            // Select this exclusively
            exclusiveType = type
            blendableTypes.clear()
            updateAllButtonStates()
            saveTypePrefs()
            if (isPlaying) sendServiceIntent(WhiteNoiseService.ACTION_START)
            else requestNotificationPermissionAndStart()
        }
    }

    private fun onBlendableClicked(type: NoiseType, button: MaterialButton) {
        if (blendableTypes.contains(type)) {
            // Already selected — deselect it
            blendableTypes.remove(type)
            button.isChecked = false
            if (blendableTypes.isEmpty() && exclusiveType == null) {
                // Was the only playing sound — stop
                if (isPlaying) stopWhiteNoise()
            } else {
                saveTypePrefs()
                if (isPlaying) sendServiceIntent(WhiteNoiseService.ACTION_START)
            }
            saveTypePrefs()
        } else {
            // Add to blendable selection
            if (exclusiveType != null) {
                // Clear exclusive selection
                exclusiveType = null
            }
            if (blendableTypes.size >= 2) {
                // Evict oldest
                blendableTypes.remove(blendableTypes.first())
            }
            blendableTypes.add(type)
            updateAllButtonStates()
            saveTypePrefs()
            if (isPlaying) sendServiceIntent(WhiteNoiseService.ACTION_START)
            else requestNotificationPermissionAndStart()
        }
    }

    private fun updateAllButtonStates() {
        binding.btnWhite.isChecked = exclusiveType == NoiseType.WHITE
        binding.btnPink.isChecked  = exclusiveType == NoiseType.PINK
        binding.btnBrown.isChecked = exclusiveType == NoiseType.BROWN
        binding.btnOcean.isChecked = NoiseType.OCEAN in blendableTypes
        binding.btnRain.isChecked  = NoiseType.RAIN  in blendableTypes
binding.btnFan.isChecked   = NoiseType.FAN   in blendableTypes
        binding.btnFire.isChecked  = NoiseType.FIRE  in blendableTypes
    }

    private fun saveTypePrefs() {
        val list = getActiveTypes()
        prefs.edit()
            .putString(PREF_NOISE_TYPE_1, list.getOrNull(0)?.name ?: NoiseType.WHITE.name)
            .putString(PREF_NOISE_TYPE_2, list.getOrNull(1)?.name ?: "NONE")
            .apply()
    }

    private fun getActiveTypes(): List<NoiseType> {
        if (exclusiveType != null) return listOf(exclusiveType!!)
        return blendableTypes.toList()
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
                return
            }
        }
        startWhiteNoise()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show()
            }
            startWhiteNoise()
        }
    }

    private fun startWhiteNoise() {
        sendServiceIntent(WhiteNoiseService.ACTION_START)
        isPlaying = true
        binding.btnPlayStop.text = getString(R.string.stop)
        binding.statusText.text = getString(R.string.status_playing)
    }

    private fun stopWhiteNoise() {
        sendServiceIntent(WhiteNoiseService.ACTION_STOP)
        isPlaying = false
        binding.btnPlayStop.text = getString(R.string.play)
        binding.statusText.text = getString(R.string.status_stopped)
    }

    private fun sendServiceIntent(action: String) {
        val list = getActiveTypes()
        val intent = Intent(this, WhiteNoiseService::class.java).apply {
            this.action = action
            putExtra(WhiteNoiseService.EXTRA_VOLUME, volume)
            putExtra(WhiteNoiseService.EXTRA_NOISE_TYPE_1, list.getOrNull(0)?.name ?: NoiseType.WHITE.name)
            putExtra(WhiteNoiseService.EXTRA_NOISE_TYPE_2, list.getOrNull(1)?.name ?: "NONE")
        }
        startForegroundService(intent)
    }
}

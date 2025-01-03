package com.example.ihatebouldering

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load CameraFragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CameraFragment())
                .commit()
        }
    }
}

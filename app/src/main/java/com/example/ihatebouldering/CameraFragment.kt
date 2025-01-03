package com.example.ihatebouldering

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.example.ihatebouldering.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CameraFragment : Fragment() {

    private lateinit var binding: FragmentCameraBinding
    private lateinit var imageCapture: ImageCapture

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCameraBinding.inflate(inflater, container, false)

        // Check Camera Permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        // Capture photo on button click
        binding.captureButton.setOnClickListener {
            takePhoto()
        }

        return binding.root
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(requireActivity().windowManager.defaultDisplay.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * Takes a photo and saves it to external files directory, then sends it to OpenAI.
     */
    private fun takePhoto() {
        // If imageCapture is not yet initialized, return
        if (!this::imageCapture.isInitialized) {
            Toast.makeText(requireContext(), "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Create output file
        val photoFile = createImageFile()

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    requireActivity().runOnUiThread {
                        binding.resultTextView.text = "Error capturing photo: ${exception.message}"
                    }
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri: Uri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo capture succeeded: $savedUri")

                    // Convert file to Base64
                    val base64Image = fileToBase64(photoFile)
                    // Send image to OpenAI
                    sendImageToOpenAI(base64Image)
                }
            }
        )
    }

    /**
     * Loads an image File, resizes to 720px wide (keeping aspect ratio),
     * compresses to JPEG, and returns the Base64 string.
     */
    private fun fileToBase64(file: File): String {
        // Decode the original image from file
        val originalBitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: return "" // If decoding fails, return empty string or handle error

        // Calculate aspect ratio
        val aspectRatio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()

        // Desired width is 720px; compute the corresponding height
        val targetWidth = 512
        val targetHeight = (targetWidth / aspectRatio).toInt()

        // Create a scaled bitmap
        val resizedBitmap = Bitmap.createScaledBitmap(
            originalBitmap,
            targetWidth,
            targetHeight,
            true
        )

        // Optionally recycle the original to free up memory
        originalBitmap.recycle()

        // Compress the resized bitmap into a JPEG at ~80% quality (adjust as needed)
        val outputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        resizedBitmap.recycle()

        // Convert the compressed bytes to Base64
        val compressedBytes = outputStream.toByteArray()
        return Base64.encodeToString(compressedBytes, Base64.DEFAULT)
    }

    /**
     * Creates a temporary JPG file in external files directory.
     */
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_",  /* prefix */
            ".jpg",               /* suffix */
            storageDir            /* directory */
        )
    }

    /**
     * Send the base64-encoded image to OpenAI by including it in a text prompt.
     */
    private fun sendImageToOpenAI(base64Image: String) {
        val openAI = OpenAIClient.openAI

        // Run API calls on IO thread
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // This is a trivial example: we embed the base64 string in the prompt.
                // GPT-3.5 or GPT-4 does NOT truly "see" the image but treats the base64 text as input.
                val messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = """
            I will give a photo from bouldering and the color of the grips of the target route. Provide a short evaluation of the route, containing main challenge, secondary challenge and an international bouldering grade.

            Evaluate bouldering route by strength, grip, stamina, legs, balance, psychology. Based on this evaluation provide a main and secondary challenge for the route.

            For the route grades use The Bouldering V Scale (a.k.a the Vermin Scale). The V Scale spans from Beginner (V0-V3) to Elite (V17).
        """.trimIndent()
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = """
            [
                {
                    "type": "text",
                    "text": "Analyze the blue route from the picture below."
                },
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "data:image/jpeg;base64,$base64Image",
                        "detail": "low"
                    }
                }
            ]
        """.trimIndent()
                    )
                )

                val response = openAI.chatCompletion(
                    ChatCompletionRequest(
                    model = ModelId("gpt-4o-mini"),
                    messages = messages
                    )
                )
                val description = response.choices.firstOrNull()?.message?.content?.trim()
                    ?: "No description available."

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    binding.resultTextView.text = description
//                    binding.captureButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI call failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.resultTextView.text = "OpenAI Error: ${e.message}"
                }
            }
        }
    }

    /**
     * Check camera permission
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

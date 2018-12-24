package com.blockeq.stellarwallet.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import com.blockeq.stellarwallet.R
import com.blockeq.stellarwallet.WalletApplication
import com.blockeq.stellarwallet.helpers.Constants.Companion.STELLAR_ADDRESS_LENGTH
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.android.synthetic.main.content_enter_address.*

class EnterAddressActivity : BaseActivity(), View.OnClickListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.content_enter_address)

        setupUI()
    }

    private fun initiateScan() {
        IntentIntegrator(this).setBeepEnabled(false).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(this, "Scan cancelled", Toast.LENGTH_LONG).show()
            } else {
                addressEditText.setText(result.contents)
                nextButton.isEnabled = true
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    //region User Interface
    private fun setupUI() {
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        titleText.text = WalletApplication.userSession.getFormattedCurrentAvailableBalance(applicationContext)

        cameraImageButton.setOnClickListener(this)
        nextButton.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        when(v.id) {
            R.id.nextButton -> {
                val address = addressEditText.text.toString()

                if (address.length == STELLAR_ADDRESS_LENGTH && address != WalletApplication.localStore.stellarAccountId) {
                    startActivity(SendActivity.newIntent(this, address))

                    this.overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
                } else {
                    // Shake animation on the text
                    val shakeAnimation = AnimationUtils.loadAnimation(this, R.anim.shake)
                    addressEditText.startAnimation(shakeAnimation)
                }
            }
            R.id.cameraImageButton -> {
                initiateScan()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item != null) {
            if (item.itemId == android.R.id.home) {
                finish()
                return true
            }
        }
        return false
    }

    //endregion
}

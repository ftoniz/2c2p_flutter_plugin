package co.ichob.ccppflutterplugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ccpp.pgw.sdk.android.builder.CardTokenPaymentBuilder
import com.ccpp.pgw.sdk.android.builder.CreditCardPaymentBuilder
import com.ccpp.pgw.sdk.android.builder.TransactionRequestBuilder
import com.ccpp.pgw.sdk.android.callback.TransactionResultCallback
import com.ccpp.pgw.sdk.android.core.PGWSDK
import com.ccpp.pgw.sdk.android.enums.APIEnvironment
import com.ccpp.pgw.sdk.android.enums.APIResponseCode
import com.ccpp.pgw.sdk.android.model.api.request.TransactionRequest
import com.ccpp.pgw.sdk.android.model.api.response.TransactionResultResponse
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import io.flutter.plugin.common.PluginRegistry.Registrar

class CcppFlutterPlugin() : MethodCallHandler, FlutterPlugin, ActivityAware, ActivityResultListener {
    private var activity: Activity? = null
    private var applicationContext: Context? = null
    private var result: MethodChannel.Result? = null
    private var methodChannel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(binding.applicationContext, binding.binaryMessenger)
    }

    private fun onAttachedToEngine(applicationContext: Context, messenger: BinaryMessenger) {
        this.applicationContext = applicationContext
        this.methodChannel = MethodChannel(messenger, CHANNEL_NAME)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity;
        methodChannel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    override fun onDetachedFromActivity() {
        activity = null
        applicationContext = null
        methodChannel?.setMethodCallHandler(null)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }
    
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        this.result = result
        when (call.method) {
            "initialize" -> {
                val merchantId = call.argument<String>("merchantId")
                val isSandbox = call.argument<Boolean>("isSandBox") ?: true
                val environment = if(isSandbox) APIEnvironment.SANDBOX else APIEnvironment.PRODUCTION

                PGWSDK.builder(activity!!)
                        .setMerchantID(merchantId)
                        .setAPIEnvironment(environment)
                        .init()
                result.success("initialize success")
            }
            "paymentWithCreditCard" -> {
                val paymentToken = call.argument<String>("paymentToken") ?: ""
                val ccNumber = call.argument<String>("ccNumber") ?: ""
                val expMonth = call.argument<Int>("expMonth") ?: 0
                val expYear = call.argument<Int>("expYear") ?: 0
                val cvv = call.argument<String>("cvv") ?: ""
                val storeCard = call.argument<Boolean>("storeCard") ?: false
                paymentWithCreditCard(result, paymentToken, ccNumber, expMonth, expYear, cvv, storeCard)
            }
            "paymentWithToken" -> {
                val paymentToken = call.argument<String>("paymentToken") ?: ""
                val cardToken = call.argument<String>("cardToken") ?: ""
                val cvv = call.argument<String>("cvv") ?: ""
                paymentWithToken(result, paymentToken,cardToken, cvv)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun paymentWithCreditCard(result: MethodChannel.Result, paymentToken: String, ccNumber: String, expMonth: Int, expYear: Int, cvv: String, storeCard: Boolean) {
        //Construct credit card request
        val creditCardPayment = CreditCardPaymentBuilder(ccNumber)
                .setExpiryMonth(expMonth)
                .setExpiryYear(expYear)
                .setSecurityCode(cvv)
                .setStoreCard(storeCard)
                .build()

        //Construct transaction request
        val transactionRequest = TransactionRequestBuilder(paymentToken)
                .withCreditCardPayment(creditCardPayment)
                .build()

        //Execute payment request
        proceedTransaction(result, transactionRequest)
    }

    private fun paymentWithToken(result: MethodChannel.Result, paymentToken: String, cardToken: String, securityCode: String) {
        val creditCardPayment = CardTokenPaymentBuilder(cardToken)
                .setSecurityCode(securityCode)
                .build()

        val transactionRequest = TransactionRequestBuilder(paymentToken)
                .withCreditCardPayment(creditCardPayment)
                .build()

        proceedTransaction(result, transactionRequest)
    }

    private fun proceedTransaction(result: MethodChannel.Result, transactionRequest: TransactionRequest) {
        PGWSDK.getInstance().proceedTransaction(transactionRequest, object : TransactionResultCallback {
            override fun onResponse(response: TransactionResultResponse) {

                //For 3DS
                when (response.responseCode) {
                    APIResponseCode.TRANSACTION_AUTHENTICATE -> {
                        val redirectUrl = response.redirectUrl
                        val i = Intent(activity, WebViewActivity::class.java)
                        i.putExtra("redirect", redirectUrl)
                        activity?.startActivityForResult(i, CCPP_AUTH_REQUEST_CODE) //Open WebView for 3DS
                    }
                    APIResponseCode.TRANSACTION_COMPLETED -> {

                        //Inquiry payment result by using transaction id.
                        val transactionID = response.transactionID
                        result.success(transactionID)
                    }
                    else -> {
                        //Get error response and display error
//                        result.error("ERROR", response.responseDescription, null)
                        result.success("ERROR " + response.responseDescription)
                    }
                }
            }

            override fun onFailure(error: Throwable) {
                //Get error response and display error
//                result.error("ERROR", error.message, null)
                result.success("ERROR " + error.message)
            }
        })
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent): Boolean {
        if (requestCode == CCPP_AUTH_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val res = intent.getStringExtra("result")
                result?.success(res)
                return true
            }
            return true
        }
        return false
    }

    companion object {
        const val CHANNEL_NAME = "co.ichob/ccpp"
        const val CCPP_AUTH_REQUEST_CODE = 5152
    }

}
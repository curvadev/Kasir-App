package com.kasirqu;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private final static int FCR = 1;
    private static final int REQUEST_PERMISSIONS = 100;

    // UUID standar untuk printer thermal Bluetooth (SPP)
    private static final UUID PRINTER_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. MENGATUR WARNA STATUS BAR BIRU & NAVIGATION BAR PUTIH
        setSystemBarColors();

        // 2. MEMBANGUN LAYOUT BERLAPIS
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#2563eb"));

        View bottomWhiteBg = new View(this);
        bottomWhiteBg.setBackgroundColor(Color.WHITE);
        int navHeight = (int) (100 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, navHeight);
        bottomParams.gravity = Gravity.BOTTOM;
        root.addView(bottomWhiteBg, bottomParams);

        FrameLayout contentWrapper = new FrameLayout(this);
        contentWrapper.setFitsSystemWindows(true);

        webView = new WebView(this);
        contentWrapper.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(contentWrapper, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 3. TAMBAHKAN LAYOUT SPLASH SCREEN DI ATAS WEBVIEW
        RelativeLayout splashLayout = new RelativeLayout(this);
        splashLayout.setBackgroundColor(Color.parseColor("#ffffff"));

        ImageView splashImage = new ImageView(this);
        splashImage.setImageResource(R.drawable.splash_logo);
        splashImage.setAdjustViewBounds(true);

        RelativeLayout.LayoutParams imgParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        imgParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        int margin = (int) (32 * getResources().getDisplayMetrics().density);
        imgParams.setMargins(margin, 0, margin, 0);
        splashImage.setLayoutParams(imgParams);

        splashLayout.addView(splashImage);

        root.addView(splashLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // HILANGKAN SPLASH SCREEN SETELAH 2.5 DETIK
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                splashLayout.animate().alpha(0f).setDuration(500).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        splashLayout.setVisibility(View.GONE);
                    }
                });
            }
        }, 2500);

        // 4. MINTA IZIN KAMERA & BLUETOOTH
        cekDanMintaIzin();

        // =========================================================================
        // TRIK MUTLAK: MEMATIKAN GOOGLE PASSWORD MANAGER (AUTOFILL)
        // =========================================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Melarang Android melakukan scanning Autofill pada seluruh layar dan WebView
            getWindow().getDecorView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        // Bersihkan cache dan data form yang mungkin tertinggal
        webView.clearFormData();
        webView.clearCache(true);
        // =========================================================================

        // 5. PENGATURAN WEBVIEW
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        // Mematikan penyimpanan form data & password bawaan WebView
        webSettings.setSaveFormData(false);
        webSettings.setSavePassword(false);

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("print://")) {
                    try {
                        String encodedText = url.replace("print://", "");
                        String rawText = URLDecoder.decode(encodedText, "UTF-8");
                        kirimKePrinterBluetooth(rawText);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Gagal mencetak: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.grant(request.getResources());
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent;
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean isImage = false;

                if (acceptTypes != null && acceptTypes.length > 0) {
                    for (String type : acceptTypes) {
                        if (type != null && type.contains("image")) {
                            isImage = true;
                            break;
                        }
                    }
                }

                if (isImage) {
                    intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    intent.setType("image/*");
                } else {
                    intent = fileChooserParams.createIntent();
                }

                try {
                    startActivityForResult(intent, FCR);
                } catch (Exception e) {
                    uploadMessage = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("https://curva.web.id/kasir-app/");
    }

    private void setSystemBarColors() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            
            window.setStatusBarColor(Color.parseColor("#2563eb"));
            window.setNavigationBarColor(Color.WHITE);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int flags = window.getDecorView().getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; 
                window.getDecorView().setSystemUiVisibility(flags);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int flags = window.getDecorView().getSystemUiVisibility();
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                window.getDecorView().setSystemUiVisibility(flags);
            }
        }
    }

    public class WebAppInterface {
        @android.webkit.JavascriptInterface
        public void printBluetooth(String data) {
            kirimKePrinterBluetooth(data);
        }

        @android.webkit.JavascriptInterface
        public void cetakStruk(String data) {
            kirimKePrinterBluetooth(data);
        }

        @android.webkit.JavascriptInterface
        public void printReceipt(String text, String base64) {
            if (base64 == null) base64 = "";
            kirimKePrinterBluetooth(text + "|||SPLIT|||" + base64);
        }

        @android.webkit.JavascriptInterface
        public void shareImage(String base64) {
            bagikanKeWhatsApp(base64);
        }
    }

    private void bagikanKeWhatsApp(String base64Data) {
        try {
            byte[] decodedString = Base64.decode(base64Data, Base64.DEFAULT);
            Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
            
            File cachePath = new File(getCacheDir(), "images");
            cachePath.mkdirs();
            File file = new File(cachePath, "struk_pembayaran.jpg");
            FileOutputStream stream = new FileOutputStream(file);
            
            Bitmap newBitmap = Bitmap.createBitmap(decodedByte.getWidth(), decodedByte.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(newBitmap);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(decodedByte, 0, 0, null);
            
            newBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
            stream.close();

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);

            if (contentUri != null) {
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.putExtra(Intent.EXTRA_TEXT, "Berikut adalah bukti transaksi Anda. Terima Kasih!");
                
                shareIntent.setPackage("com.whatsapp");
                try {
                    startActivity(shareIntent);
                } catch (android.content.ActivityNotFoundException ex) {
                    shareIntent.setPackage(null);
                    startActivity(Intent.createChooser(shareIntent, "Bagikan Struk via..."));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Gagal memproses gambar struk", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // PROSES EKSEKUSI BLUETOOTH PRINTER & DECODE LOGO
    private void kirimKePrinterBluetooth(final String dataDikirim) {
        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Perangkat tidak mendukung Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Nyalakan Bluetooth terlebih dahulu!", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Izin Bluetooth Connect belum diberikan!", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String teksStruk = dataDikirim;
                String logoBase64 = "";

                if (dataDikirim != null && dataDikirim.contains("|||SPLIT|||")) {
                    String[] parts = dataDikirim.split("\\|\\|\\|SPLIT\\|\\|\\|");
                    teksStruk = parts.length > 0 ? parts[0] : "";
                    logoBase64 = parts.length > 1 ? parts[1] : "";
                }

                byte[] imageBytes = null;
                
                if (logoBase64 != null && logoBase64.startsWith("data:image")) {
                    try {
                        String pureBase64 = logoBase64.substring(logoBase64.indexOf(",") + 1);
                        byte[] decodedString = Base64.decode(pureBase64, Base64.DEFAULT);
                        Bitmap originalBitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                        
                        if (originalBitmap != null) {
                            int targetWidth = 240; 
                            targetWidth = (targetWidth / 8) * 8; 
                            int targetHeight = (int) (originalBitmap.getHeight() * ((float) targetWidth / originalBitmap.getWidth()));
                            
                            Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true);
                            
                            Bitmap whiteBgBitmap = Bitmap.createBitmap(scaledBitmap.getWidth(), scaledBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(whiteBgBitmap);
                            canvas.drawColor(Color.WHITE); 
                            canvas.drawBitmap(scaledBitmap, 0, 0, null);

                            imageBytes = decodeBitmapToEscPos(whiteBgBitmap);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                boolean berhasilCetak = false;
                for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                    try {
                        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(PRINTER_UUID);
                        socket.connect();
                        OutputStream outputStream = socket.getOutputStream();

                        outputStream.write(new byte[]{27, 64});
                        Thread.sleep(100);

                        if (imageBytes != null) {
                            outputStream.write(new byte[]{27, 97, 1}); 
                            outputStream.flush();
                            
                            int chunkSize = 256;
                            for (int i = 0; i < imageBytes.length; i += chunkSize) {
                                int length = Math.min(chunkSize, imageBytes.length - i);
                                outputStream.write(imageBytes, i, length);
                                outputStream.flush();
                                Thread.sleep(15);
                            }
                            outputStream.write("\n\n".getBytes());
                            outputStream.flush();
                            Thread.sleep(200);
                        }

                        outputStream.write(new byte[]{27, 97, 0}); 
                        outputStream.write(teksStruk.getBytes("UTF-8"));
                        outputStream.write("\n\n\n".getBytes());
                        outputStream.flush();
                        
                        outputStream.close();
                        socket.close();

                        berhasilCetak = true;
                        break; 
                    } catch (Exception e) {}
                }

                final boolean finalBerhasil = berhasilCetak;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalBerhasil) {
                            Toast.makeText(MainActivity.this, "Struk berhasil dicetak!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Printer Bluetooth tidak terhubung/mati.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private byte[] decodeBitmapToEscPos(Bitmap bmp) {
        int width = bmp.getWidth();
        int height = bmp.getHeight();
        int xBytes = (width + 7) / 8;
        byte[] data = new byte[8 + (xBytes * height)];
        data[0] = 0x1D; data[1] = 0x76; data[2] = 0x30; data[3] = 0x00;
        data[4] = (byte) (xBytes % 256); data[5] = (byte) (xBytes / 256);
        data[6] = (byte) (height % 256); data[7] = (byte) (height / 256);
        int idx = 8;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < xBytes * 8; x += 8) {
                byte b = 0;
                for (int k = 0; k < 8; k++) {
                    if (x + k < width) {
                        int pixel = bmp.getPixel(x + k, y);
                        if (Color.alpha(pixel) < 128) continue; 
                        
                        int luminance = (int) (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114);
                        if (luminance < 200) { 
                            b |= (1 << (7 - k));
                        }
                    }
                }
                data[idx++] = b;
            }
        }
        return data;
    }

    private void cekDanMintaIzin() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.CAMERA
                }, REQUEST_PERMISSIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FCR) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}

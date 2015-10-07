package com.k_nakamura.horiojapan.kousaku.saitama_u.ncwc_androidsensors;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k_nakamura.horiojapan.kousaku.saitama_u.ncwc_androidsensors.usbserial.driver.UsbSerialPort;
import com.k_nakamura.horiojapan.kousaku.saitama_u.ncwc_androidsensors.usbserial.util.HexDump;
import com.k_nakamura.horiojapan.kousaku.saitama_u.ncwc_androidsensors.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class SendDataActivity extends AppCompatActivity implements LocationListener, SensorEventListener {

    //GPS系用の変数
    private SensorManager mSensorManager;
    private LocationManager locationManager;
    private String text = "----Log----\n";

    private int preLatitude;
    private int preLongitude;

    private boolean firstdata;

    private int gpsSendDataArray[] = {0,0,0,};

    // Viewの変数
    private TextView mTitleTextView;
    private TextView mDumpTextView;
    private ScrollView mScrollView;

    //角度取得系用の変数
    private float[] mAcceleration;
    private float[] mGeomagnetic;

    private float[] mLowpassAttitude = {0,0,0};

    private int orientationSendDataArray[] = {0,0,0,};


    //通信関連
    private static UsbSerialPort sPort = null;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    //Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    SendDataActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            SendDataActivity.this.updateReceivedData(data);
                        }
                    });
                }
            };

    // スリープにならないようにするやつ関連
    PowerManager mPowerManager;
    WakeLock mWakeLock;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);

        mTitleTextView = (TextView) findViewById(R.id.demoTitle);
        mDumpTextView = (TextView) findViewById(R.id.consoleText);
        mScrollView = (ScrollView) findViewById(R.id.demoScroller);

        //setContentView(R.layout.activity_main);
        //textView = (TextView)findViewById(R.id.text_view);
        //status_view = (TextView)findViewById(R.id.status_view1);

        //変数の初期化
        preLatitude = 0;
        preLongitude = 0;
        firstdata = true;

        // LocationManager インスタンス生成
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        //SensorManagerインスタンス生成
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE );

        final boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!gpsEnabled) {
            // GPSを設定するように促す
            enableLocationSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        //SCREEN_DIM_WAKE_LOCK or PARTIAL_WAKE_LOCK
        mWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "tag");
        mWakeLock.acquire();

        if (locationManager != null) {
            // minTime = 500msec, minDistance = 1m
            //minTime msec 経過するか minDistance m 移動したら更新
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 1, this);
            mDumpTextView.setText("GPSstart");
        }
        else{
            text += "locationManager=null\n";
            mDumpTextView.setText(text);
        }

        // センサーの取得
        List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        // センサーマネージャへリスナーを登録
        for (Sensor sensor : sensors) {
            if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                mSensorManager.registerListener(this, sensor,  SensorManager.SENSOR_DELAY_FASTEST);
            }
            if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
            }
        }


        // シリアルポートを開く
        if (sPort == null)
        {
            mTitleTextView.setText("No serial device.");
        }
        else
        {
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            UsbDeviceConnection connection = usbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                mTitleTextView.setText("Opening device failed");
                return;
            }

            try {
                sPort.open(connection);
                sPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (IOException e) {
                //Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                mTitleTextView.setText("Error opening device: " + e.getMessage());
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                return;
            }
            mTitleTextView.setText("Serial device: " + sPort.getClass().getSimpleName());
        }
        // SerialIOManagerを設定する．データ取得イベントとかをやってくれるやつだと思われ．
        onDeviceStateChange();

    }

    @Override
    protected void onPause() {
        // センサーやらGPSやらを止める
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }

        if( mSensorManager != null){
            mSensorManager.unregisterListener(this);
        }

        super.onPause();
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        // 画面が消えないようにするやつを止める
        mWakeLock.release();

    }


    /*--↓--GPS関連--↓--*/
    @Override
    public void onLocationChanged(Location location) {

        //textの表示
        text += "----------\n";
        text += "Latitude="+ String.valueOf(location.getLatitude())+"\n";
        text += "Longitude="+ String.valueOf(location.getLongitude())+"\n";

        // Get the estimated accuracy of this location, in meters.
        // We define accuracy as the radius of 68% confidence. In other words,
        // if you draw a circle centered at this location's latitude and longitude,
        // and with a radius equal to the accuracy, then there is a 68% probability
        // that the true location is inside the circle.
        text += "Accuracy="+ String.valueOf(location.getAccuracy())+"\n";

        //text += "Altitude="+ String.valueOf(location.getAltitude())+"\n";
        //text += "Time="+ String.valueOf(location.getTime())+"\n";
        //text += "Speed="+ String.valueOf(location.getSpeed())+"\n";

        // Get the bearing, in degrees.
        // Bearing is the horizontal direction of travel of this device,
        // and is not related to the device orientation.
        // It is guaranteed to be in the range (0.0, 360.0] if the device has a bearing.
        //text += "Bearing="+ String.valueOf(location.getBearing())+"\n";
        text += "----------\n";

        mDumpTextView.setText(text);

        //Portが開いているとき
        if(sPort != null){
            if (firstdata) {
                //送るやつ
                /*
                //初期値を記録
                preLatitude = (int) (location.getLatitude() * 1000000);
                preLongitude = (int) (location.getLongitude() * 1000000);
                int Accuracy = (int) (location.getAccuracy() * 10);

                //送信用バッファ作成
                byte[] senddata = new byte[11];
                senddata[0] = (byte) 3;
                senddata[1] = (byte) (preLatitude >> 24);
                senddata[2] = (byte) (preLatitude >> 16);
                senddata[3] = (byte) (preLatitude >> 8);
                senddata[4] = (byte) (preLatitude);
                senddata[5] = (byte) (preLongitude >> 24);
                senddata[6] = (byte) (preLongitude >> 16);
                senddata[7] = (byte) (preLongitude >> 8);
                senddata[8] = (byte) (preLongitude);
                senddata[9] = (byte) (Accuracy >> 8);
                senddata[10] = (byte) (Accuracy);

                //データ送信
                try {
                    sPort.write(senddata, 5000);
                    mTitleTextView.setText("First writed\n");
                    text += "first write \n";
                    mDumpTextView.setText(text);
                } catch (IOException e) {
                    mTitleTextView.setText("Can't write\n");
                }
                */

                // データを送信用変数に格納
                gpsSendDataArray[0] = (int) (location.getLatitude() * 1000000);
                gpsSendDataArray[1] = (int) (location.getLongitude() * 1000000);
                gpsSendDataArray[2] = (int) (location.getAccuracy() * 10);

                //firstdata = !firstdata;

            } else {
                //符号の記録用
                int signs = 0;

                //緯度・経度・精度の変化量をintで下6桁分取得
                int dLatitude = (int)(location.getLatitude() * 1000000 - preLatitude);
                int dLongitude = (int)(location.getLongitude() * 1000000 - preLongitude);
                int Accuracy = (int)(location.getAccuracy() * 10);

                //負の値を送ると受信側が面倒なので，
                //各値が負ならsignsに記録して正にしておく
                if(dLatitude < 0){
                    signs = signs | 0b1;
                    dLatitude = -dLatitude;
                }
                if(dLongitude < 0){
                    signs = signs | 0b10;
                    dLongitude = -dLongitude;
                }

                //値の更新
                preLatitude = (int) (location.getLatitude() * 1000000);
                preLongitude = (int) (location.getLongitude() * 1000000);

                //送信用バッファ作成
                byte[] senddata = new byte[11];
                senddata[0] = (byte)1;
                senddata[1] = (byte) ( dLatitude >> 16 );
                senddata[2] = (byte) ( dLatitude >> 8 );
                senddata[3] = (byte) ( dLatitude );
                senddata[4] = (byte) ( dLongitude >> 16 );
                senddata[5] = (byte) ( dLongitude >> 8 );
                senddata[6] = (byte) ( dLongitude );
                senddata[7] = (byte) ( Accuracy >> 8 );
                senddata[8] = (byte) ( Accuracy );
                senddata[9] = (byte)  signs;

                //データ送信
                try {
                    sPort.write(senddata, 5000);
                    mTitleTextView.setText("Writed\n");
                    text += "writed \n";
                    mDumpTextView.setText(text);
                } catch (IOException e) {
                    mTitleTextView.setText("Can't write\n");
                }
            }
        }


    }
    public void sendGPSdata(){
        //送信用バッファ作成
        byte[] senddata = new byte[11];
        senddata[0] = (byte) 3;
        senddata[1] = (byte) (gpsSendDataArray[0] >> 24);
        senddata[2] = (byte) (gpsSendDataArray[0] >> 16);
        senddata[3] = (byte) (gpsSendDataArray[0] >> 8);
        senddata[4] = (byte) (gpsSendDataArray[0]);
        senddata[5] = (byte) (gpsSendDataArray[1] >> 24);
        senddata[6] = (byte) (gpsSendDataArray[1] >> 16);
        senddata[7] = (byte) (gpsSendDataArray[1] >> 8);
        senddata[8] = (byte) (gpsSendDataArray[1]);
        senddata[9] = (byte) (gpsSendDataArray[2] >> 8);
        senddata[10] = (byte) (gpsSendDataArray[2]);

        //データ送信
        try {
            sPort.write(senddata, 5000);
            mTitleTextView.setText("GPS writed\n");
            text += "GPS write \n";
            mDumpTextView.setText(text);
        } catch (IOException e) {
            mTitleTextView.setText("Can't write\n");
        }
    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        /*switch (status) {
            case LocationProvider.AVAILABLE:
                status_view.setText("LocationProvider.AVAILABLE\n");

                break;
            case LocationProvider.OUT_OF_SERVICE:
                status_view.setText("LocationProvider.OUT_OF_SERVICE\n");
                break;
            case LocationProvider.TEMPORARILY_UNAVAILABLE:
                status_view.setText("LocationProvider.TEMPORARILY_UNAVAILABLE\n");
                break;
        }*/
    }

    // GPSを有効にする画面を表示
    private void enableLocationSettings() {
        Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(settingsIntent);
    }
    /*--↑--GPS関連--↑--*/


    /*--↓――方位関連--↓--*/
    @Override
    public void onSensorChanged(SensorEvent event) {
        // 精度の低いデータは捨てる
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE)
            return;
        // 地磁気センサー、加速度センサーの値を取得
        switch (event.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD:
                mGeomagnetic = event.values.clone();
                break;
            case Sensor.TYPE_ACCELEROMETER:
                mAcceleration = event.values.clone();
                break;
        }
        // 両方のデータが揃ったら計算を行う
        if (mGeomagnetic != null && mAcceleration != null) {
            float   SmoothingCoefficient = 0.1f;

            float[] inR = new float[9];
            //float[] outR = new float[9];
            float[] mOrientation = new float[3];
            SensorManager.getRotationMatrix(inR, null, mAcceleration, mGeomagnetic);
            // Activityの表示が縦固定で、端末表面が自分を向いている場合
            //SensorManager.remapCoordinateSystem(inR, SensorManager.AXIS_X, SensorManager.AXIS_Z, outR);
            //SensorManager.getOrientation(outR, mOrientation);
            // 今回は基準姿勢デフォルトでOK
            SensorManager.getOrientation(inR, mOrientation);

            for (int i = 0 ; i < 3 ; i++)
            {
                mLowpassAttitude[i] = mOrientation[i] * SmoothingCoefficient + mLowpassAttitude[i] * (1.0f - SmoothingCoefficient);
            }
            // Z軸方向Azimuth, X軸方向Pitch, Y軸方向Roll
            String buf =
                    "---------- Orientation --------\n" +
                            String.format("Azimuth\n\t%.2f\n", rad2deg(mOrientation[0])) +
                            String.format("Pitch\n\t%.2f\n", rad2deg(mOrientation[1])) +
                            String.format("Roll\n\t%.2f\n", rad2deg(mOrientation[2])) +
                    "---------- Lowpass Orientation --------\n" +
                            String.format("Azimuth\n\t%.2f\n", rad2deg(mLowpassAttitude[0])) +
                            String.format("Pitch\n\t%.2f\n", rad2deg(mLowpassAttitude[1])) +
                            String.format("Roll\n\t%.2f\n", rad2deg(mLowpassAttitude[2]));
            TextView t = (TextView) findViewById(R.id.orientationView);
            t.setText(buf);

            if(sPort != null) {
                //byte[] orientationData = new byte[7];
                float[] senddata = {rad2deg(mLowpassAttitude[0]),rad2deg(mLowpassAttitude[1]),rad2deg(mLowpassAttitude[2])};

                for(int i = 0 ; i < 3 ; i++)
                {
                    senddata[i] += 180f;
                    senddata[i] *= 100f;
                    orientationSendDataArray[i] = (int)senddata[i];
                }


                /*
                orientationData[0] = (byte) 2;
                orientationData[1] = (byte) ((int) senddata[0] >> 8);
                orientationData[2] = (byte) ((int) senddata[0]);
                orientationData[3] = (byte) ((int) senddata[1] >> 8);
                orientationData[4] = (byte) ((int) senddata[1]);
                orientationData[5] = (byte) ((int) senddata[2] >> 8);
                orientationData[6] = (byte) ((int) senddata[2]);

                ///*
                orientationData[0] = (byte) 2;
                orientationData[1] = (byte) ((int) ((rad2deg(mLowpassAttitude[0]) + 180)* 100) >> 8);
                orientationData[2] = (byte) ((int) (rad2deg(mLowpassAttitude[0]) + 180)* 100);
                orientationData[3] = (byte) ((int) ((rad2deg(mLowpassAttitude[1]) + 180)* 100) >> 8);
                orientationData[4] = (byte) ((int) (rad2deg(mLowpassAttitude[1]) + 180)* 100);
                orientationData[5] = (byte) ((int) ((rad2deg(mLowpassAttitude[2]) + 180)* 100) >> 8);
                orientationData[6] = (byte) ((int) (rad2deg(mLowpassAttitude[2]) + 180)* 100);


                try {
                    sPort.write(orientationData, 5000);
                    mTitleTextView.setText("Writed\n");
                } catch (IOException e) {
                    mTitleTextView.setText("Can't write\n");
                }
                */
            }

            mGeomagnetic = null;
            mAcceleration = null;

        }
    }

    private void sendOrientationData()
    {
        byte[] orientationData = new byte[7];

        orientationData[0] = (byte) 2;
        orientationData[1] = (byte) (orientationSendDataArray[0] >> 8);
        orientationData[2] = (byte) (orientationSendDataArray[0]);
        orientationData[3] = (byte) (orientationSendDataArray[1] >> 8);
        orientationData[4] = (byte) (orientationSendDataArray[1]);
        orientationData[5] = (byte) (orientationSendDataArray[2] >> 8);
        orientationData[6] = (byte) (orientationSendDataArray[2]);

        try {
            sPort.write(orientationData, 5000);
            mTitleTextView.setText("Orientation writed\n");
        } catch (IOException e) {
            mTitleTextView.setText("Can't write\n");
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private float rad2deg( float rad ) {
        return rad * (float) 180.0 / (float) Math.PI;
    }

    /*--↑――方位関連--↑--*/

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    private void updateReceivedData(byte[] data) {

        String message = "Read " + data.length + " bytes: \n";
        for (int i = 0;i < data.length;i++) {
            message += String.valueOf((int)data[i]) + "\n";
        }
        mDumpTextView.append(message);
        //mScrollView.smoothScrollTo(0, mDumpTextView.getBottom());

        if( data[0] == 1 ) sendGPSdata();
        else if( data[0] == 2) sendOrientationData();

    }


    //画面遷移用のメソッド
    static void show(Context context, UsbSerialPort port) {
        sPort = port;
        final Intent intent = new Intent(context, SendDataActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }

    //メニュー作成
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_devicelist, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case R.id.searchdevice:
                intent = new Intent(this, DeviceListActivity.class);
                startActivity(intent);
                break;
        }

        return super.onOptionsItemSelected(item);
    }

}

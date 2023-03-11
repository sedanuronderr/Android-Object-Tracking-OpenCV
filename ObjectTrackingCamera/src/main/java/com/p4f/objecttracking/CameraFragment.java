package com.p4f.objecttracking;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
//import android.text.Spannable;
//import android.text.SpannableStringBuilder;
//import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvException;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.tracking.Tracker;
import org.opencv.tracking.TrackerCSRT;
import org.opencv.tracking.TrackerKCF;
import org.opencv.tracking.TrackerMIL;
import org.opencv.tracking.TrackerMOSSE;
import org.opencv.tracking.TrackerMedianFlow;
import org.opencv.tracking.TrackerTLD;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraFragment extends Fragment implements IOnBackPressed  {

    final String TAG = "CameraFragment";

    private TextureView mTextureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    enum Drawing{
        DRAWING,
        TRACKING,
        CLEAR,
    }

    //camera device
    private String cameraId;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private Size imageDimension;
    private ImageReader imageReader;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private final Size CamResolution = new Size(1280,720);
    private CameraCaptureSession mCaptureSession;
    /** this prevent the app from exiting before closing the camera. */
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private Mat mImageGrabInit;
    private Mat mImageGrab;
    private Bitmap mBitmapGrab = null;
    private OverlayView mTrackingOverlay;
    private org.opencv.core.Rect2d mInitRectangle = null;
    private Point[] mPoints = new Point[2];
    private boolean mProcessing = false;
    private com.p4f.objecttracking.CameraFragment.Drawing mDrawing = com.p4f.objecttracking.CameraFragment.Drawing.DRAWING;
    private boolean mTargetLocked = false;
    private boolean mShowCordinate = false;

    private Tracker mTracker;
    private Menu mMenu;

    //bluetooth device
    private enum Connected { False, Pending, True }
    private String newline = "\r\n";
    private TextView receiveText;
    private SerialSocket socket;
    private SerialService service;
    private boolean initialStart = true;
    private com.p4f.objecttracking.CameraFragment.Connected connected = com.p4f.objecttracking.CameraFragment.Connected.False;
    private final int REQUEST_CONNECT_CODE = 68;
    private String mBluetoothDevAddr = "";
    private String mSelectedTracker = "TrackerMedianFlow";


    BluetoothAdapter myBluetoothAdapter;
    Intent btEnablingIntent;
    int requestCodeForeEnable;

    BluetoothDevice[] btArray;
    ListView pairedlist;
    String address;
    BluetoothSocket btSocket = null;

    private boolean isBtConnected = false;

    private ProgressDialog progress;

    private Set<BluetoothDevice> pairedDevice;
    private static final String APP_NAME = "STARMAP";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(getActivity(), "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        setHasOptionsMenu(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(textureListener);
        }
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, getActivity(), null);
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        //closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    public void onDestroy() {
        Log.e(TAG, "onDestroy");
        closeCamera();
        if (connected != com.p4f.objecttracking.CameraFragment.Connected.False)
            disconnectBLE();
        super.onDestroy();

    }

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /*
     * UI
     */

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_camera, container, false);
        mTextureView = (TextureView) view.findViewById(R.id.texture);
        mTrackingOverlay = (OverlayView)view.findViewById(R.id.tracking_overlay);
        assert ( mTextureView != null && mTrackingOverlay !=null) ;
        mTextureView.setSurfaceTextureListener(textureListener);


        myBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();


        btEnablingIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        requestCodeForeEnable = 1;
        pairedlist = view.findViewById(R.id.listview2);
        return view;
    }


    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final CaptureResult partialResult) {}

                @Override
                public void onCaptureCompleted(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final TotalCaptureResult result) {}
            };


    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraOpenCloseLock.release();
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            createCameraPreview();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraOpenCloseLock.release();
            cameraDevice.close();
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            cameraOpenCloseLock.release();
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener(){
        @Override
        public void onImageAvailable(ImageReader reader){
            final Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            if(mProcessing){
                image.close();
                return;
            }

            mProcessing = true;

//            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
//            byte[] bytes = new byte[buffer.capacity()];
//            buffer.get(bytes);
//            Bitmap bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);

            if(mTargetLocked) {
                // image to byte array
                ByteBuffer bb = image.getPlanes()[0].getBuffer();
                byte[] data = new byte[bb.remaining()];
                bb.get(data);
                mImageGrab = Imgcodecs.imdecode(new MatOfByte(data), Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
                org.opencv.core.Core.transpose(mImageGrab, mImageGrab);
                org.opencv.core.Core.flip(mImageGrab, mImageGrab, 1);
                org.opencv.imgproc.Imgproc.resize(mImageGrab, mImageGrab, new org.opencv.core.Size(240,320));
            }
//            Bitmap bmp = null;
//            Mat tmp = new Mat (mImageGrab.rows(), mImageGrab.cols(), CvType.CV_8U, new Scalar(4));
//            try {
//                Imgproc.cvtColor(mImageGrab, tmp, Imgproc.COLOR_RGB2BGRA);
//                bmp = Bitmap.createBitmap(tmp.cols(), tmp.rows(), Bitmap.Config.ARGB_8888);
//                Utils.matToBitmap(tmp, bmp);
//            }
//            catch (CvException e){
//                Log.d("Exception",e.getMessage());
//            }

            image.close();
            processing();
        }
    };

    private void processing(){
        //TODO:do processing
        // Get the features for tracking
        if(mTargetLocked) {
            if(mDrawing== Drawing.DRAWING) {
                Log.d(TAG, ":target locked ");

                int minX = (int)((float)(mPoints[0].x)/mTrackingOverlay.getWidth()*mImageGrab.cols());
                int minY = (int)((float)(mPoints[0].y)/mTrackingOverlay.getHeight()*mImageGrab.rows());

                mInitRectangle = new org.opencv.core.Rect2d(minX, minY, 20, 20);
                Log.d(TAG, ":minx " + Integer.toString(minX) + " " + Integer.toString(minY) );


                mImageGrabInit = new Mat();
                mImageGrab.copyTo(mImageGrabInit);

                if(mSelectedTracker.equals("TrackerMedianFlow")) {
                    mTracker = TrackerMedianFlow.create();
                }else if(mSelectedTracker.equals("TrackerCSRT")) {
                    mTracker = TrackerCSRT.create();
                }else if(mSelectedTracker.equals("TrackerKCF")) {
                    mTracker = TrackerKCF.create();
                }else if(mSelectedTracker.equals("TrackerMOSSE")) {
                    mTracker = TrackerMOSSE.create();
                }else if(mSelectedTracker.equals("TrackerTLD")) {
                    mTracker = TrackerTLD.create();
                }else if(mSelectedTracker.equals("TrackerMIL")) {
                    mTracker = TrackerMIL.create();
                }

                mTracker.init(mImageGrabInit, mInitRectangle);
                mDrawing = Drawing.TRACKING;

                //TODO: DEBUG
                org.opencv.core.Rect testRect = new org.opencv.core.Rect(minX, minY, 20, 20);
                Mat roi = new Mat(mImageGrab, testRect);
                Bitmap bmp = null;
                Mat tmp = new Mat (roi.rows(), roi.cols(), CvType.CV_8U, new Scalar(4));
                try {
                    Imgproc.cvtColor(roi, tmp, Imgproc.COLOR_RGB2BGRA);
                    bmp = Bitmap.createBitmap(tmp.cols(), tmp.rows(), Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(tmp, bmp);
                }
                catch (CvException e){
                    Log.d("Exception",e.getMessage());
                }

            }else{
                org.opencv.core.Rect2d trackingRectangle = new org.opencv.core.Rect2d(0, 0, 1,1);
                mTracker.update(mImageGrab, trackingRectangle);

//                //TODO: DEBUG
//                org.opencv.core.Rect testRect = new org.opencv.core.Rect((int)trackingRectangle.x,
//                                                                        (int)trackingRectangle.y,
//                                                                        (int)trackingRectangle.width,
//                                                                        (int)trackingRectangle.height);
//                Mat roi = new Mat(mImageGrab, testRect);
//                Bitmap bmp = null;
//                Mat tmp = new Mat (roi.rows(), roi.cols(), CvType.CV_8U, new Scalar(4));
//                try {
//                    Imgproc.cvtColor(roi, tmp, Imgproc.COLOR_RGB2BGRA);
//                    bmp = Bitmap.createBitmap(tmp.cols(), tmp.rows(), Bitmap.Config.ARGB_8888);
//                    Utils.matToBitmap(tmp, bmp);
//                }
//                catch (CvException e){
//                    Log.d("Exception",e.getMessage());
//                    mTargetLocked = false;
//                    mDrawing = Drawing.DRAWING;
//                }

                mPoints[0].x = (int)(trackingRectangle.x*(float)mTrackingOverlay.getWidth()/(float)mImageGrab.cols());
                mPoints[0].y = (int)(trackingRectangle.y*(float)mTrackingOverlay.getHeight()/(float)mImageGrab.rows());
                mPoints[1].x = mPoints[0].x+ (int)(trackingRectangle.width*(float)mTrackingOverlay.getWidth()/(float)mImageGrab.cols());
                mPoints[1].y = mPoints[0].y +(int)(trackingRectangle.height*(float)mTrackingOverlay.getHeight()/(float)mImageGrab.rows());

                Log.d(TAG, ":mp " + Integer.toString(mPoints[0].x) + "mp " + Integer.toString(mPoints[0].y) );


                mTrackingOverlay.postInvalidate();
                if(connected == Connected.True) {
                    String dataBle = Integer.toString((mPoints[0].x + mPoints[1].x) / 2) + "," +
                            Integer.toString(mTrackingOverlay.getWidth()) + "," +
                            Integer.toString((mPoints[0].y + mPoints[1].y) / 2) + "," +
                            Integer.toString(mTrackingOverlay.getHeight());
                 sendSignal(dataBle);

                }
            }
        }else{
            if (mTracker != null) {
                mTracker.clear();
                mTracker = null;
            }
        }
        mProcessing = false;
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            imageReader = ImageReader.newInstance(CamResolution.getWidth(), CamResolution.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, mBackgroundHandler);
            previewRequestBuilder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    mCaptureSession = cameraCaptureSession;
                    // Auto focus should be continuous for camera preview.
                    previewRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    // Flash is automatically enabled when necessary.
                    previewRequestBuilder.set(
                            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                    // Finally, we start displaying the camera preview.
                    previewRequest = previewRequestBuilder.build();
                    try {
                        cameraCaptureSession.setRepeatingRequest(previewRequest, captureCallback, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(getActivity(), "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);

            for (int i=0; i<mPoints.length;i++){
                mPoints[i] = new Point(0,0);
            }


            mTrackingOverlay.addCallback(
                    new OverlayView.DrawCallback() {
                        @Override
                        public void drawCallback(Canvas canvas) {
                            if(mDrawing != com.p4f.objecttracking.CameraFragment.Drawing.CLEAR) {
                                Paint paint = new Paint();
                                paint.setColor(Color.rgb(0, 0, 255));
                                paint.setStrokeWidth(10);
                                paint.setStyle(Paint.Style.STROKE);
                                canvas.drawCircle(mPoints[0].x, mPoints[0].y, 100, paint);
                            }
                        }
                    }
            );
            mTrackingOverlay.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    final int X = (int) event.getX();
                    final int Y = (int) event.getY();
                    Log.d(TAG, ": " + Integer.toString(X) + " " + Integer.toString(Y) );
                    switch (event.getAction() & MotionEvent.ACTION_MASK) {
                        case MotionEvent.ACTION_DOWN:
//                            Log.d(TAG, ": " +  "MotionEvent.ACTION_DOWN" );
                            mTargetLocked=true;
                            //mDrawing = com.p4f.objecttracking.CameraFragment.Drawing.DRAWING;
                            mDrawing = Drawing.DRAWING;
                            mPoints[0].x = X;
                            mPoints[0].y = Y;
                            mTrackingOverlay.postInvalidate();
                            break;

                    }
                    if(mTargetLocked==true){
                        mMenu.getItem(2).setEnabled(false);
                    }else{
                        mMenu.getItem(2).setEnabled(true);
                    }
                    return true;
                }
            });
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            imageDimension = CamResolution;
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
//                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle(R.string.camera_permission_title);
                builder.setMessage(R.string.camera_permission_message);
                builder.setPositiveButton(android.R.string.ok,
                        (dialog, which) -> requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION));
                builder.show();
                return;
            }
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(cameraId, stateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
        Log.e(TAG, "openCamera 0");
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
            cameraOpenCloseLock.release();
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /*
     * SerialListener
     */

    /*
     * Serial + UI
     */

    private void status(String str) {
        mMenu.getItem(3).setTitle("BLE "+str);
    }

    private void connectBLE() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mBluetoothDevAddr);
            String deviceName = device.getName() != null ? device.getName() : device.getAddress();
            status("connecting...");
            connected = com.p4f.objecttracking.CameraFragment.Connected.Pending;
            socket = new SerialSocket();

        } catch (Exception e) {
        }
    }

    private void disconnectBLE() {
        connected = com.p4f.objecttracking.CameraFragment.Connected.False;
        service.disconnect();
        socket.disconnect();
        socket = null;
    }

    private void sendBLE(String str) {
        if(connected != com.p4f.objecttracking.CameraFragment.Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data = (str + newline).getBytes();
            socket.write(data);
        } catch (Exception e) {

        }
    }





    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.layout.menu_camera, menu);
        mMenu = menu;
        mMenu.getItem(3).setEnabled(false);

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.ble_setup) {




            return true;
        } else if(id == R.id.camera_cordinate){
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

            final CheckBox showCooridinate = new CheckBox(getActivity());
            showCooridinate.setText("Show coordinate");
            showCooridinate.setChecked(mShowCordinate);
            showCooridinate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                                           @Override
                                                           public void onCheckedChanged(CompoundButton buttonView,boolean isChecked) {
                                                               mShowCordinate = isChecked;
                                                           }
                                                       }
            );

            LinearLayout lay = new LinearLayout(getActivity());
            lay.setPadding(0,30,0,0);
            lay.setGravity(Gravity.CENTER_HORIZONTAL);
            lay.addView(showCooridinate);

            builder.setView(lay);

            // Set up the buttons
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });


            builder.setCancelable(false);
            Dialog dialog = builder.show();

            return true;
        } else if (id ==R.id.tracker_type){
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Tracker Selection");

            final String[] radioBtnNames = {
                    "TrackerMedianFlow",
                    "TrackerCSRT",
                    "TrackerKCF",
                    "TrackerMOSSE",
                    "TrackerTLD",
                    "TrackerMIL",
            };

            final RadioButton[] rb = new RadioButton[radioBtnNames.length];
            RadioGroup rg = new RadioGroup(getActivity()); //create the RadioGroup
            rg.setOrientation(RadioGroup.VERTICAL);

            for(int i=0; i < radioBtnNames.length; i++){
                rb[i]  = new RadioButton(getActivity());
                rb[i].setText(" " + radioBtnNames[i]);
                rb[i].setId(i + 100);
                rg.addView(rb[i]);
                if(radioBtnNames[i].equals(mSelectedTracker)){
                    rb[i].setChecked(true);
                }
            }

            // This overrides the radiogroup onCheckListener
            rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener()
            {
                public void onCheckedChanged(RadioGroup group, int checkedId){
                    // This will get the radiobutton that has changed in its check state
                    RadioButton checkedRadioButton = (RadioButton)group.findViewById(checkedId);
                    // This puts the value (true/false) into the variable
                    boolean isChecked = checkedRadioButton.isChecked();
                    if (isChecked)
                    {
                        // Changes the textview's text to "Checked: example radiobutton text"
                        mSelectedTracker = checkedRadioButton.getText().toString().replace(" ", "");
                    }
                }
            });

            LinearLayout lay = new LinearLayout(getActivity());
            lay.setPadding(0,30,0,0);
            lay.setGravity(Gravity.CENTER_HORIZONTAL);
            lay.addView(rg);

            builder.setView(lay);

            // Set up the buttons
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });


            builder.setCancelable(false);
            Dialog dialog = builder.show();

            return true;
        } else if (id == R.id.blue) {
            success_blue();
            return true;
        } else if (id == R.id.ble) {
listDevice();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }
    void success_blue() {
        if (myBluetoothAdapter == null) {
            Toast.makeText(requireActivity(), "Bluetooth does not support", Toast.LENGTH_LONG).show();


        } else {

            if (!myBluetoothAdapter.isEnabled()) {

                startActivityForResult(btEnablingIntent, requestCodeForeEnable);

            }

        }
    }


    private void listDevice() {

        pairedDevice = myBluetoothAdapter.getBondedDevices();


        ArrayList list = new ArrayList();
        if (pairedDevice.size() > 0) {
            for (BluetoothDevice bt : pairedDevice) {
                list.add(bt.getName() + "\n" + bt.getAddress());


            }


        } else {
            Toast.makeText(requireActivity(), "Eşleşmiş cihaz yok", Toast.LENGTH_SHORT).show();
        }

        final ArrayAdapter adapter = new ArrayAdapter(requireActivity(), android.R.layout.simple_list_item_1, list);
        pairedlist.setAdapter(adapter);
        pairedlist.setOnItemClickListener(cihazSec);


    }

    public AdapterView.OnItemClickListener cihazSec = new AdapterView.OnItemClickListener() {

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {


            String info = ((TextView) view).getText().toString();
            address = info.substring(info.length() - 17);
            //Yeni bir Activity baslatman icin bir intent tanımlıyoruz
            Toast.makeText(requireActivity(), "adress" + address, Toast.LENGTH_SHORT).show();
            new CameraFragment.BTbaglan().execute();
        }
    };

    private void Disconnect() {
        if (btSocket != null) {
            try {
                btSocket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    @Override
    public boolean onBackPressed() {
        Disconnect();
        return false;
    }


    private class BTbaglan extends AsyncTask<Void, Void, Void> {
        private boolean ConnectSuccess = true;

        @Override
        protected void onPreExecute() {
            progress = ProgressDialog.show(requireActivity(), "Bağlanıyor..", "Lütfen Bekleyin");
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                if (btSocket == null || !isBtConnected) {
                    myBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    BluetoothDevice cihaz = myBluetoothAdapter.getRemoteDevice(address);


                    btSocket = cihaz.createRfcommSocketToServiceRecord(MY_UUID);
                    BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                    btSocket.connect();



                }
            }
            catch (IOException e){
                ConnectSuccess =false;

            }



            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            super.onPostExecute(unused);
            if(!ConnectSuccess){
                Toast.makeText(requireActivity(), "Bağlantı hatası", Toast.LENGTH_SHORT).show();

            }else{
                Toast.makeText(requireActivity(), "Bağlantı başarılı", Toast.LENGTH_SHORT).show();
                isBtConnected = true;
            }
            progress.dismiss();
        }
    }


    private void sendSignal ( String number ) {
        if ( btSocket != null ) {
            try {
                btSocket.getOutputStream().write(number.toString().getBytes());

                Toast.makeText(requireActivity(), "Başarılı data ", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(requireActivity(), "Başarısız data ", Toast.LENGTH_SHORT).show();
            }
        }
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(resultCode == requestCodeForeEnable){
            if(resultCode == RESULT_OK){
                Toast.makeText(requireActivity(),"Bluetooth is enable",Toast.LENGTH_LONG).show();


            }else if(resultCode == RESULT_CANCELED){
                Toast.makeText(requireActivity(),"Bluetooth Enabling Cancelled",Toast.LENGTH_LONG).show();

            }
        }
    }




}

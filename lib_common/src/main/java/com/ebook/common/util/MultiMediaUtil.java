package com.ebook.common.util;

import android.Manifest;
import android.content.Intent;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AppCompatActivity;

import com.ebook.common.BuildConfig;
import com.tbruyelle.rxpermissions2.RxPermissions;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.reactivex.functions.Consumer;
import me.nereo.multi_image_selector.MultiImageSelector;

/**
 * Description: <h3>多媒体工具类</h3>
 * <ul>
 * <li>1.图片选择器，可算多张图片</li>
 * <li>2.拍照</li>
 * <li>3.拍视频</li>
 * <li>4.创建一个图片路径</li>
 * <li>5.创建一个视频路径</li>
 * </ul>
 * <h3>注意事项：</h3>
 * <ul><li>1. 拍照、拍视频、选择图片完成的回调都在onActivityResult中回调的</l1>
 * <li>2.选择图片获取：List<String> path = data.getStringArrayListExtra(MultiImageSelectorActivity.EXTRA_RESULT)</li>
 * </ul>

 */
public class MultiMediaUtil {
    public static final int SELECT_IMAGE = 1001;
    public static final int TAKE_PHONE = 1002;
    public static final int TAKE_VIDEO = 1003;

    /**
     * 打开图片选择器，选择图片<br>
     * 来获取图片
     *
     * @param activity
     * @param count：选择图片个数
     * @param requestcode
     */
    public static void pohotoSelect(FragmentActivity activity, int count, int requestcode) {
        pohotoSelect(activity, null, count, requestcode);
    }

    public static void pohotoSelect(Fragment fragment, int count, int requestcode) {
        pohotoSelect(null, fragment, count, requestcode);
    }

    private static void pohotoSelect(final FragmentActivity activity, final Fragment fragment, final int count, final int requestcode) {
        RxPermissions rxPermissions = null;
        if (activity != null) {
            rxPermissions = new RxPermissions(activity);
        } else if (fragment != null) {
            rxPermissions = new RxPermissions(fragment);
        }
        rxPermissions.request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE).subscribe(new Consumer<Boolean>() {
            @Override
            public void accept(Boolean aBoolean) throws Exception {
                if (aBoolean) {
                    if (activity != null) {
                        MultiImageSelector.create().showCamera(false).count(count).single().multi()
                                //.origin(ArrayList<String>)
                                .start(activity, requestcode);
                    } else if (fragment != null) {
                        MultiImageSelector.create().showCamera(false).count(count).single().multi()
                                //.origin(ArrayList<String>)
                                .start(fragment, requestcode);
                    }
                } else {
                    ToastUtil.showToast("无读写外部存储设备权限");
                }
            }
        });


    }

    /**
     * 拍照
     *
     * @param activity
     * @param path:照片存放的路径
     * @param requestcode
     */
    public static void takePhoto(FragmentActivity activity, String path, int requestcode) {
        takePhoto(activity, null, path, requestcode);
    }

    public static void takePhoto(Fragment fragment, String path, int requestcode) {
        takePhoto(null, fragment, path, requestcode);
    }

    private static void takePhoto(final FragmentActivity activity, final Fragment fragment, final String path, final int requestcode) {
        if (activity == null && fragment == null) {
            return;
        }
        RxPermissions rxPermissions = null;
        if (activity != null) {
            rxPermissions = new RxPermissions(activity);
        } else if (fragment != null) {
            rxPermissions = new RxPermissions(fragment);
        }

        rxPermissions.request(Manifest.permission.CAMERA).subscribe(new Consumer<Boolean>() {
            @Override
            public void accept(Boolean aBoolean) throws Exception {
                if (aBoolean) {
                    File file = new File(path);

                    try {
                        if (file.createNewFile()) {
                            Intent intent = new Intent();
                            intent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);
                            intent.addCategory(Intent.CATEGORY_DEFAULT);
                            if (activity != null) {
                                intent.putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(activity, BuildConfig.LIBRARY_PACKAGE_NAME, file));
                                activity.startActivityForResult(intent, requestcode);
                            } else if (fragment != null) {
                                intent.putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(fragment.getContext(), BuildConfig.LIBRARY_PACKAGE_NAME, file));
                                fragment.startActivityForResult(intent, requestcode);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        ToastUtil.showToast("无法启动拍照程序");
                    }
                } else {
                    ToastUtil.showToast("无摄像头权限,无法进行拍照!");
                }
            }
        });
    }


    /**
     * 拍视频
     *
     * @param activity
     * @param path:视频存放的路径
     * @param requestcode
     */
    public static void takeVideo(final FragmentActivity activity, final String path, final int requestcode) {
        takeVideo(activity, null, path, requestcode);
    }

    public static void takeVideo(final Fragment fragment, final String path, final int requestcode) {
        takeVideo(null, fragment, path, requestcode);
    }

    private static void takeVideo(final FragmentActivity activity, final Fragment fragment, final String path, final int requestcode) {
        if (activity == null && fragment == null) {
            return;
        }
        RxPermissions rxPermissions = null;
        if (activity != null) {
            rxPermissions = new RxPermissions(activity);
        } else if (fragment != null) {
            rxPermissions = new RxPermissions(fragment);
        }
        rxPermissions.request(Manifest.permission.CAMERA).subscribe(new Consumer<Boolean>() {
            @Override
            public void accept(Boolean aBoolean) throws Exception {
                if (aBoolean) {
                    File file = new File(path);
                    try {
                        if (file.createNewFile()) {
                            Intent intent = new Intent();
                            intent.setAction(MediaStore.ACTION_VIDEO_CAPTURE);
                            intent.addCategory(Intent.CATEGORY_DEFAULT);
                            //intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(file));
                            if (activity != null) {
                                intent.putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(activity, BuildConfig.LIBRARY_PACKAGE_NAME, file));
                                activity.startActivityForResult(intent, requestcode);
                            } else if (fragment != null) {
                                intent.putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(fragment.getContext(), BuildConfig.LIBRARY_PACKAGE_NAME, file));
                                fragment.startActivityForResult(intent, requestcode);
                            }

                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        ToastUtil.showToast("无法启动拍视频程序");
                    }
                } else {
                    ToastUtil.showToast("无摄像头权限,无法进行拍视频!");
                }
            }
        });
    }

    //获取图片路径
    public static String getPhotoPath(AppCompatActivity activity) {
        String filename = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg";
        return activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES).getAbsolutePath() + File.separator + filename;
    }

    //获取视频的路径
    public static String getVideoPath(AppCompatActivity activity) {
        String filename = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".3gp";
        return activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES).getAbsolutePath() + File.separator + filename;
    }
}

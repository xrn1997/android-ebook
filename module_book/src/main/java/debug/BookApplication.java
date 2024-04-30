package debug;

import android.content.Intent;

import com.ebook.api.RetrofitManager;
import com.ebook.book.service.DownloadService;
import com.ebook.db.GreenDaoManager;
import com.xrn1997.common.BaseApplication;


public class BookApplication extends BaseApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        RetrofitManager.init(this);
        GreenDaoManager.init(this);
        startService(new Intent(this, DownloadService.class));
    }
}

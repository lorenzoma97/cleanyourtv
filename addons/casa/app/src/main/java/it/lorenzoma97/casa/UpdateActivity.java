package it.lorenzoma97.casa;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Installs the ready updates (Updater) one after another when the owner chose "Installa" on Home. The system
 * installer shows its own confirmation for each and checks the signature. Started by the launcher; anyone else
 * starting it can only offer the same verified files, which still need the owner's OK.
 */
public class UpdateActivity extends Activity {
    private static final String STATUS = "it.lorenzoma97.casa.UPDATE_STATUS";
    private List<File> files;
    private int next;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        files = Updater.ready(this);   // an app already updated is no longer in it
        if (STATUS.equals(getIntent().getAction())) onNewIntent(getIntent());   // the activity was gone meanwhile
        else installNext();
    }

    /** The installer's answer for the session just committed. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            installNext();
        } else {
            String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.w("Casa", "update not installed: " + status + " " + why);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(this, getString(R.string.update_failed), Toast.LENGTH_LONG).show();
            }
            finish();
        }
    }

    private void installNext() {
        if (files == null || next >= files.size()) {
            finish();
            return;
        }
        File f = files.get(next++);
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(Updater.packageOf(f));
        try {
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream out = session.openWrite("base.apk", 0, f.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                PendingIntent answer = PendingIntent.getActivity(this, id,
                        new Intent(this, UpdateActivity.class).setAction(STATUS),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(answer.getIntentSender());
            }
        } catch (IOException | SecurityException e) {
            Log.w("Casa", "update session: " + e);
            Toast.makeText(this, getString(R.string.update_failed), Toast.LENGTH_LONG).show();
            finish();
        }
    }
}

package org.ovirt.mobile.movirt.ui;

import android.app.DialogFragment;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.OptionsItem;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.Receiver;
import org.androidannotations.annotations.UiThread;
import org.ovirt.mobile.movirt.Broadcasts;
import org.ovirt.mobile.movirt.R;
import org.ovirt.mobile.movirt.model.ConnectionInfo;
import org.ovirt.mobile.movirt.provider.ProviderFacade;
import org.ovirt.mobile.movirt.sync.SyncUtils;
import org.ovirt.mobile.movirt.ui.dialogs.ErrorDialogFragment;

/**
 * Class that represents base Activity for entire moVirt app. Every Activity should extends this if
 * you want to see basic ActionBar options, etc
 * Created by Nika on 25.06.2015.
 */

@EActivity
@OptionsMenu(R.menu.movirt)
public abstract class MovirtActivity extends ActionBarLoaderActivity implements HasProgressBar {
    public static final int FIRST_CHILD_LOADER = 1;
    private static final int CONNECTION_INFO_LOADER = 0;
    @Bean
    protected ProviderFacade providerFacade;
    @Bean
    protected SyncUtils syncUtils;
    private LoaderManager loaderManager;
    private ConnectionInfo connectionInfo;
    private ConnectionInfoLoader connectionInfoLoader;
    private ProgressBar progress;

    @Override
    public LoaderManager getSupportLoaderManager() {
        return this.loaderManager;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loaderManager = super.getSupportLoaderManager();
        connectionInfoLoader = new ConnectionInfoLoader();
        connectionInfo = new ConnectionInfo();
        loaderManager.initLoader(CONNECTION_INFO_LOADER, null, connectionInfoLoader);
    }

    @Override
    public void restartLoader() {
        loaderManager.restartLoader(CONNECTION_INFO_LOADER, null, connectionInfoLoader);
    }

    @Override
    public void destroyLoader() {
        loaderManager.destroyLoader(CONNECTION_INFO_LOADER);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem connection = menu.findItem(R.id.menu_connection);
        ConnectionInfo.State state = connectionInfo.getState();
        boolean connectionVisibility = state == ConnectionInfo.State.FAILED || state == ConnectionInfo.State.FAILED_REPEATEDLY;
        connection.setVisible(connectionVisibility);
        return super.onPrepareOptionsMenu(menu);
    }

    @OptionsItem(R.id.menu_connection)
    public void onConnectionInfo() {
        DialogFragment dialogFragment =
                ErrorDialogFragment.newInstance(connectionInfo.getMessage(this));
        dialogFragment.show(getFragmentManager(), "connection_info");
    }

    @OptionsItem(R.id.action_refresh)
    @Background
    public void onRefresh() {
        syncUtils.triggerRefresh();
    }

    /**
     * Method to set progress bar defined by child class and handled by parent class
     *
     * @param progress ProgressBar defined in child layout
     */
    public void setProgressBar(ProgressBar progress) {
        this.progress = progress;
        hideProgressBar();
    }

    @UiThread(propagation = UiThread.Propagation.REUSE)
    @Override
    public void showProgressBar() {
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
    }

    @UiThread(propagation = UiThread.Propagation.REUSE)
    @Override
    public void hideProgressBar() {
        if (progress != null) {
            progress.setVisibility(View.GONE);
        }
    }

    @UiThread(propagation = UiThread.Propagation.REUSE)
    @Receiver(actions = Broadcasts.IN_SYNC, registerAt = Receiver.RegisterAt.OnResumeOnPause)
    protected void syncingChanged(@Receiver.Extra(Broadcasts.Extras.SYNCING) boolean syncing) {
        if (syncing && progress != null) {
            showProgressBar();
        } else {
            hideProgressBar();
        }
    }

    private class ConnectionInfoLoader implements android.support.v4.app.LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public android.support.v4.content.Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return providerFacade.query(ConnectionInfo.class).asLoader();
        }

        @Override
        public void onLoadFinished(android.support.v4.content.Loader<Cursor> loader, Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                connectionInfo.initFromCursor(data);
                invalidateOptionsMenu();
            }
        }

        @Override
        public void onLoaderReset(android.support.v4.content.Loader<Cursor> loader) {
        }
    }
}

package com.intelliviz.stockhawk.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.intelliviz.stockhawk.R;
import com.intelliviz.stockhawk.data.StockQuoteContract;
import com.intelliviz.stockhawk.rest.QuoteCursorAdapter;
import com.intelliviz.stockhawk.rest.RecyclerViewItemClickListener;
import com.intelliviz.stockhawk.rest.Utils;
import com.intelliviz.stockhawk.syncadapter.StockSyncAdapter;
import com.intelliviz.stockhawk.touch_helper.SimpleItemTouchHelperCallback;
import com.melnykov.fab.FloatingActionButton;

import java.lang.ref.WeakReference;

import butterknife.Bind;
import butterknife.ButterKnife;

public class StockListFragment extends Fragment implements
        LoaderManager.LoaderCallbacks<Cursor>, OnLoadStockListener, SharedPreferences.OnSharedPreferenceChangeListener {
    public static final int REQUEST_ITEM = 0;
    private static final String PREF_NEED_TO_SYNC = "need_to_sync";
    private static final String TAG = StockListFragment.class.getSimpleName();
    private CharSequence mTitle;
    private static final int STOCK_LOADER_ID = 0;
    public static final int STATUS_LOADER_ID = 1;
    private QuoteCursorAdapter mCursorAdapter;
    private ItemTouchHelper mItemTouchHelper;
    //private boolean mIsConnected;
    private Account mAccount;
    private OnStockSelectListener mListener;

    @Bind(R.id.empty_view) TextView mEmptyView;
    @Bind(R.id.recycler_view) RecyclerView mRecyclerView;
    @Bind(R.id.fab) FloatingActionButton mFab;
    @Bind(R.id.swipe_refresh_layout) SwipeRefreshLayout mSwipeRefreshLayout;

    public interface OnStockSelectListener {
        void onSelectStock(String symbol, String data);
    }

    public StockListFragment() {
        // Required empty public constructor
    }

    public static StockListFragment newInstance() {
        StockListFragment fragment = new StockListFragment();
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_stock_list, container, false);
        ButterKnife.bind(this, view);

        ConnectivityManager cm =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
        // Run the initialize task service so that some stocks appear upon an empty database
        boolean needToSync = true; //sp.getBoolean(PREF_NEED_TO_SYNC, true);
        if(needToSync) {
            syncStockData();
        }

        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        getLoaderManager().initLoader(STOCK_LOADER_ID, null, this);
        getLoaderManager().initLoader(STATUS_LOADER_ID, null, this);

        mCursorAdapter = new QuoteCursorAdapter(getContext(), null);
        mRecyclerView.addOnItemTouchListener(new RecyclerViewItemClickListener(getContext(),
                new RecyclerViewItemClickListener.OnItemClickListener() {
                    @Override
                    public void onItemClick(View v, int position) {
                        String symbol = "";
                        if(mCursorAdapter.getCursor().moveToPosition(position)) {
                            Cursor cursor = mCursorAdapter.getCursor();
                            int symbolIndex = cursor.getColumnIndex(StockQuoteContract.QuotesEntry.COLUMN_SYMBOL);
                            symbol = cursor.getString(symbolIndex);
                        }
                        new StockHistoryAsyncTask(mListener, symbol).execute("");
                    }
                }));
        mRecyclerView.setAdapter(mCursorAdapter);

        mFab.attachToRecyclerView(mRecyclerView);
        mFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Utils.isNetworkAvailable(getContext())) {
                    SimpleTextDialog dialog;
                    dialog = SimpleTextDialog.newInstance("Add a Stock", "", "Symbol", false);
                    dialog.setTargetFragment(StockListFragment.this, REQUEST_ITEM);
                    dialog.show(getFragmentManager(), "Dial1");
                } else {
                    networkToast();
                }
            }
        });

        ItemTouchHelper.Callback callback = new SimpleItemTouchHelperCallback(mCursorAdapter);
        mItemTouchHelper = new ItemTouchHelper(callback);
        mItemTouchHelper.attachToRecyclerView(mRecyclerView);

        mTitle = getActivity().getTitle();

        Bundle bundle = new Bundle();
        bundle.putString(StockSyncAdapter.EXTRA_COMMAND, "update");

        ContentResolver.addPeriodicSync(
                mAccount,
                StockQuoteContract.CONTENT_AUTHORITY,
                bundle,
                StockSyncAdapter.SYNC_INTERVAL);

        mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                Utils.forceSyncUpdate(getContext());
            }
        });

        AppCompatActivity activity = (AppCompatActivity)getActivity();
        ActionBar actionBar = activity.getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
        }

        return view;
    }

    @Override
    public void onAttach(Context context) {
        mListener = (OnStockSelectListener)context;
        super.onAttach(context);
    }

    @Override
    public void onDetach() {
        mListener = null;
        super.onDetach();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // This narrows the return to only the stocks that are most current.
        Loader<Cursor> loader = null;
        if(STOCK_LOADER_ID == id) {
            loader = new CursorLoader(getContext(), StockQuoteContract.QuotesEntry.CONTENT_URI,
                    new String[]{StockQuoteContract.QuotesEntry._ID, StockQuoteContract.QuotesEntry.COLUMN_SYMBOL,
                            StockQuoteContract.QuotesEntry.COLUMN_BID_PRICE, StockQuoteContract.QuotesEntry.COLUMN_PERCENT_CHANGE,
                            StockQuoteContract.QuotesEntry.COLUMN_CHANGE, StockQuoteContract.QuotesEntry.COLUMN_ISUP},
                    StockQuoteContract.QuotesEntry.COLUMN_ISCURRENT + " = ?",
                    new String[]{"1"},
                    null);
        } else if(STATUS_LOADER_ID == id) {
            loader =  new CursorLoader(getContext(), StockQuoteContract.StatusEntry.CONTENT_URI,
                    null,
                    null,
                    null,
                    null);
        }

        return loader;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        if(loader.getId() == STOCK_LOADER_ID) {
            if (cursor.moveToFirst()) {
                mEmptyView.setVisibility(View.GONE);
                mRecyclerView.setVisibility(View.VISIBLE);
            } else {
                mEmptyView.setVisibility(View.VISIBLE);
                mRecyclerView.setVisibility(View.GONE);
                updateEmptyView();
            }
            mCursorAdapter.swapCursor(cursor);
        } else if(loader.getId() == STATUS_LOADER_ID) {
            if (cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(StockQuoteContract.StatusEntry.COLUMN_STATUS);
                int status = cursor.getInt(index);
                if(StockQuoteContract.StatusEntry.STATUS_UPDATING == status) {

                } else {

                }
            }
            mSwipeRefreshLayout.setRefreshing(false);
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mCursorAdapter.swapCursor(null);
    }

    public void networkToast() {
        Toast.makeText(getContext(), getString(R.string.network_toast), Toast.LENGTH_SHORT).show();
    }

    private void syncStockData() {
        if(mAccount == null) {
            mAccount = createSyncAccount(getContext());
        }
        if(mAccount != null) {
            ContentResolver.setIsSyncable(mAccount, StockQuoteContract.CONTENT_AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(mAccount, StockQuoteContract.CONTENT_AUTHORITY, true);

            Bundle bundle = new Bundle();
            bundle.putString(StockSyncAdapter.EXTRA_COMMAND, "update");
            bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            ContentResolver.requestSync(mAccount, StockQuoteContract.CONTENT_AUTHORITY, bundle);
        }
    }

    private void addStock(String stock) {
        mAccount = createSyncAccount(getContext());
        if(mAccount != null) {
            ContentResolver.setIsSyncable(mAccount, StockQuoteContract.CONTENT_AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(mAccount, StockQuoteContract.CONTENT_AUTHORITY, true);

            Bundle bundle = new Bundle();
            bundle.putString(StockSyncAdapter.EXTRA_COMMAND, "add");
            bundle.putString(StockSyncAdapter.EXTRA_STOCK, stock);
            bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            ContentResolver.requestSync(mAccount, StockQuoteContract.CONTENT_AUTHORITY, bundle);
        }
    }

    private Account createSyncAccount(Context context) {
        Account newAccount = new Account(
                StockQuoteContract.ACCOUNT, StockQuoteContract.ACCOUNT_TYPE);
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
        if (accountManager.addAccountExplicitly(newAccount, null, null)) {
            ContentResolver.setIsSyncable(newAccount, StockQuoteContract.CONTENT_AUTHORITY, 1);
            return newAccount;
        } else {
            Log.d(TAG, "Account already exists");
            return newAccount;
        }
    }

    @Override
    public void onLoadStock(Cursor cursor) {
        if(cursor == null || !cursor.moveToFirst()) {
            return;
        }
    }

    @Override
    public void onResume() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
        sp.registerOnSharedPreferenceChangeListener(this);
        super.onResume();
    }

    @Override
    public void onPause() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
        sp.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(key.equals(getString(R.string.pref_location_status_key))) {
            @StockSyncAdapter.LocationStatus int location = Utils.getLoctionStatus(getContext());
            if(location == StockSyncAdapter.LOCATION_STATUS_STOCK_NOT_FOUND) {
                Toast.makeText(getContext(), R.string.no_stock_found, Toast.LENGTH_SHORT).show();
            } else {
                updateEmptyView();
            }
        }
    }

    private void updateEmptyView() {
        if(mCursorAdapter.getItemCount() == 0) {
            int message;
            @StockSyncAdapter.LocationStatus int location = Utils.getLoctionStatus(getContext());
            switch(location) {
                case StockSyncAdapter.LOCATION_STATUS_SERVER_DOWN:
                    message = R.string.empty_stock_list_server_down;
                    break;
                case StockSyncAdapter.LOCATION_STATUS_SERVER_INVALID:
                    message = R.string.empty_stock_list_server_error;
                    break;
                default:
                    if(!Utils.isNetworkAvailable(getContext())) {
                        message = R.string.no_stock_info_no_network;
                    } else {
                        message = R.string.no_stock_info_add_stock;
                    }
            }

            mEmptyView.setText(message);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == REQUEST_ITEM) {
            if (resultCode == Activity.RESULT_OK) {
                String symbol = data.getStringExtra(SimpleTextDialog.EXTRA_TEXT);
                Cursor c = getContext().getContentResolver().query(StockQuoteContract.QuotesEntry.CONTENT_URI,
                        new String[]{StockQuoteContract.QuotesEntry.COLUMN_SYMBOL}, StockQuoteContract.QuotesEntry.COLUMN_SYMBOL + "= ?",
                        new String[]{symbol.toString()}, null);
                if (c.getCount() != 0) {
                    Toast toast =
                            Toast.makeText(getContext(), getActivity().getResources().getString(R.string.stock_already_saved),
                                    Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.CENTER, Gravity.CENTER, 0);
                    toast.show();
                    return;
                } else {
                    // Add the stock to DB
                    symbol = symbol.toUpperCase();
                    addStock(symbol);
                }
            }
        }

    }

    private static class StockQueryHandler extends AsyncQueryHandler {

        private WeakReference<OnLoadStockListener> mListener;

        public StockQueryHandler(ContentResolver cr, OnLoadStockListener listener) {
            super(cr);
            mListener = new WeakReference<>(listener);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            final OnLoadStockListener listener = mListener.get();
            if(listener != null) {
                listener.onLoadStock(cursor);
            }
        }
    }
}

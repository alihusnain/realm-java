/*
 * Copyright 2015 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.realm.exceptions.RealmFileException;
import io.realm.exceptions.RealmMigrationNeededException;
import io.realm.internal.InvalidRow;
import io.realm.internal.RealmObjectProxy;
import io.realm.internal.SharedRealm;
import io.realm.internal.ColumnInfo;
import io.realm.internal.Row;
import io.realm.internal.Table;
import io.realm.internal.UncheckedRow;
import io.realm.internal.async.RealmThreadPoolExecutor;
import io.realm.log.AndroidLogger;
import io.realm.log.RealmLog;
import rx.Observable;

/**
 * Base class for all Realm instances.
 *
 * @see io.realm.Realm
 * @see io.realm.DynamicRealm
 */
@SuppressWarnings("WeakerAccess")
abstract class BaseRealm implements Closeable {
    protected static final long UNVERSIONED = -1;
    private static final String INCORRECT_THREAD_CLOSE_MESSAGE =
            "Realm access from incorrect thread. Realm instance can only be closed on the thread it was created.";
    private static final String INCORRECT_THREAD_MESSAGE =
            "Realm access from incorrect thread. Realm objects can only be accessed on the thread they were created.";
    private static final String CLOSED_REALM_MESSAGE =
            "This Realm instance has already been closed, making it unusable.";
    private static final String NOT_IN_TRANSACTION_MESSAGE =
            "Changing Realm data can only be done from inside a transaction.";

    // Thread pool for all async operations (Query & transaction)
    static final RealmThreadPoolExecutor asyncTaskExecutor = RealmThreadPoolExecutor.newDefaultExecutor();

    final long threadId;
    protected RealmConfiguration configuration;
    protected SharedRealm sharedRealm;

    RealmSchema schema;
    HandlerController handlerController;

    static {
        //noinspection ConstantConditions
        RealmLog.add(BuildConfig.DEBUG ? new AndroidLogger(Log.DEBUG) : new AndroidLogger(Log.WARN));
    }

    protected BaseRealm(RealmConfiguration configuration) {
        this.threadId = Thread.currentThread().getId();
        this.configuration = configuration;

        this.handlerController = new HandlerController(this);
        this.sharedRealm = SharedRealm.getInstance(configuration, new AndroidNotifier(this.handlerController));
        this.schema = new RealmSchema(this);

        if (handlerController.isAutoRefreshAvailable()) {
            setAutoRefresh(true);
        }
    }

    /**
     * Sets the auto-refresh status of the Realm instance.
     * <p>
     * Auto-refresh is a feature that enables automatic update of the current Realm instance and all its derived objects
     * (RealmResults and RealmObject instances) when a commit is performed on a Realm acting on the same file in
     * another thread. This feature is only available if the Realm instance lives on a {@link android.os.Looper} enabled
     * thread.
     *
     * @param autoRefresh {@code true} will turn auto-refresh on, {@code false} will turn it off.
     * @throws IllegalStateException if called from a non-Looper thread.
     */
    public void setAutoRefresh(boolean autoRefresh) {
        checkIfValid();
        handlerController.checkCanBeAutoRefreshed();
        handlerController.setAutoRefresh(autoRefresh);
    }

    /**
     * Retrieves the auto-refresh status of the Realm instance.
     *
     * @return the auto-refresh status.
     */
    public boolean isAutoRefresh() {
        return handlerController.isAutoRefreshEnabled();
    }

    /**
     * Checks if the Realm is currently in a transaction.
     *
     * @return {@code true} if inside a transaction, {@code false} otherwise.
     */
    public boolean isInTransaction() {
        checkIfValid();
        return sharedRealm.isInTransaction();
    }

    protected void addListener(RealmChangeListener<? extends BaseRealm> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener should not be null");
        }
        checkIfValid();
        if (!handlerController.isAutoRefreshEnabled()) {
            throw new IllegalStateException("You can't register a listener from a non-Looper or IntentService thread.");
        }
        handlerController.addChangeListener(listener);
    }

    /**
     * Removes the specified change listener.
     *
     * @param listener the change listener to be removed.
     * @throws IllegalArgumentException if the change listener is {@code null}.
     * @throws IllegalStateException if you try to remove a listener from a non-Looper Thread.
     * @see io.realm.RealmChangeListener
     */
    public void removeChangeListener(RealmChangeListener<? extends BaseRealm> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener should not be null");
        }
        checkIfValid();
        if (!handlerController.isAutoRefreshEnabled()) {
            throw new IllegalStateException("You can't remove a listener from a non-Looper thread ");
        }
        handlerController.removeChangeListener(listener);
    }

    /**
     * Returns an RxJava Observable that monitors changes to this Realm. It will emit the current state
     * when subscribed to. Items will continually be emitted as the Realm is updated -
     * {@code onComplete} will never be called.
     * <p>
     * If you would like the {@code asObservable()} to stop emitting items, you can instruct RxJava to
     * only emit only the first item by using the {@code first()} operator:
     *
     * <pre>
     * {@code
     * realm.asObservable().first().subscribe( ... ) // You only get the results once
     * }
     * </pre>
     *
     * @return RxJava Observable that only calls {@code onNext}. It will never call {@code onComplete} or {@code OnError}.
     * @throws UnsupportedOperationException if the required RxJava framework is not on the classpath.
     * @see <a href="https://realm.io/docs/java/latest/#rxjava">RxJava and Realm</a>
     */
    public abstract Observable asObservable();

    /**
     * Removes all user-defined change listeners.
     *
     * @throws IllegalStateException if you try to remove listeners from a non-Looper Thread.
     * @see io.realm.RealmChangeListener
     */
    public void removeAllChangeListeners() {
        checkIfValid();
        if (!handlerController.isAutoRefreshEnabled()) {
            throw new IllegalStateException("You can't remove listeners from a non-Looper thread ");
        }
        handlerController.removeAllChangeListeners();
    }

    // WARNING: If this method is used after calling any async method, the old handler will still be used.
    //          package private, for test purpose only
    void setHandler(Handler handler) {
        ((AndroidNotifier)sharedRealm.realmNotifier).setHandler(handler);
    }


    /**
     * Writes a compacted copy of the Realm to the given destination File.
     * <p>
     * The destination file cannot already exist.
     * <p>
     * Note that if this is called from within a transaction it writes the current data, and not the data as it was when
     * the last transaction was committed.
     *
     * @param destination file to save the Realm to.
     * @throws RealmFileException if an error happened when accessing the underlying Realm file or writing to the
     * destination file.
     */
    public void writeCopyTo(File destination) {
        writeEncryptedCopyTo(destination, null);
    }

    /**
     * Writes a compacted and encrypted copy of the Realm to the given destination File.
     * <p>
     * The destination file cannot already exist.
     * <p>
     * Note that if this is called from within a transaction it writes the current data, and not the data as it was when
     * the last transaction was committed.
     * <p>
     *
     * @param destination file to save the Realm to.
     * @param key a 64-byte encryption key.
     * @throws IllegalArgumentException if destination argument is null.
     * @throws RealmFileException if an error happened when accessing the underlying Realm file or writing to the
     * destination file.
     */
    public void writeEncryptedCopyTo(File destination, byte[] key) {
        if (destination == null) {
            throw new IllegalArgumentException("The destination argument cannot be null");
        }
        checkIfValid();
        sharedRealm.writeCopy(destination, key);
    }

    /**
     * Blocks the current thread until new changes to the Realm are available or {@link #stopWaitForChange()}
     * is called from another thread. Once stopWaitForChange is called, all future calls to this method will
     * return false immediately.
     *
     * @return {@code true} if the Realm was updated to the latest version, {@code false} if it was
     * cancelled by calling stopWaitForChange.
     * @throws IllegalStateException if calling this from within a transaction or from a Looper thread.
     */
    public boolean waitForChange() {
        checkIfValid();
        if (isInTransaction()) {
            throw new IllegalStateException("Cannot wait for changes inside of a transaction.");
        }
        if (Looper.myLooper() != null) {
            throw new IllegalStateException("Cannot wait for changes inside a Looper thread. Use RealmChangeListeners instead.");
        }
        boolean hasChanged = sharedRealm.waitForChange();
        if (hasChanged) {
            // Since this Realm instance has been waiting for change, advance realm & refresh realm.
            sharedRealm.refresh();
            handlerController.refreshSynchronousTableViews();
        }
        return hasChanged;
    }

    /**
     * Makes any current {@link #waitForChange()} return {@code false} immediately. Once this is called,
     * all future calls to waitForChange will immediately return {@code false}.
     * <p>
     * This method is thread-safe and should _only_ be called from another thread than the one that
     * called waitForChange.
     *
     * @throws IllegalStateException if the {@link io.realm.Realm} instance has already been closed.
     */
    public void stopWaitForChange() {
        RealmCache.invokeWithLock(new RealmCache.Callback0() {
            @Override
            public void onCall() {
                // Check if the Realm instance has been closed
                if (sharedRealm == null || sharedRealm.isClosed()) {
                    throw new IllegalStateException(BaseRealm.CLOSED_REALM_MESSAGE);
                }
                sharedRealm.stopWaitForChange();
            }
        });
    }

    /**
     * Starts a transaction which must be closed by {@link io.realm.Realm#commitTransaction()} or aborted by
     * {@link io.realm.Realm#cancelTransaction()}. Transactions are used to atomically create, update and delete objects
     * within a Realm.
     * <p>
     * Before beginning a transaction, the Realm instance is updated to the latest version in order to include all
     * changes from other threads. This update does not trigger any registered {@link RealmChangeListener}.
     * <p>
     * It is therefore recommended to query for the items that should be modified from inside the transaction. Otherwise
     * there is a risk that some of the results have been deleted or modified when the transaction begins.
     * <p>
     * <pre>
     * {@code
     * // Don't do this
     * RealmResults<Person> persons = realm.where(Person.class).findAll();
     * realm.beginTransaction();
     * persons.first().setName("John");
     * realm.commitTransaction();
     *
     * // Do this instead
     * realm.beginTransaction();
     * RealmResults<Person> persons = realm.where(Person.class).findAll();
     * persons.first().setName("John");
     * realm.commitTransaction();
     * }
     * </pre>
     * <p>
     * Notice: it is not possible to nest transactions. If you start a transaction within a transaction an exception is
     * thrown.
     */
    public void beginTransaction() {
        checkIfValid();
        sharedRealm.beginTransaction();
    }

    /**
     * All changes since {@link io.realm.Realm#beginTransaction()} are persisted to disk and the Realm reverts back to
     * being read-only. An event is sent to notify all other Realm instances that a change has occurred. When the event
     * is received, the other Realms will update their objects and {@link io.realm.RealmResults} to reflect the
     * changes from this commit.
     */
    public void commitTransaction() {
        commitTransaction(true);
    }

    /**
     * Commits transaction and sends notifications to local thread.
     *
     * @param notifyLocalThread set to {@code false} to prevent this commit from triggering thread local change
     *                          listeners.
     */
    void commitTransaction(boolean notifyLocalThread) {
        checkIfValid();
        sharedRealm.commitTransaction();

        // Sometimes we don't want to notify the local thread about commits, e.g. creating a completely new Realm
        // file will make a commit in order to create the schema. Users should not be notified about that.
        if (notifyLocalThread) {
            sharedRealm.realmNotifier.notifyCommitByLocalThread();
        }
    }

    /**
     * Reverts all writes (created, updated, or deleted objects) made in the current write transaction and end the
     * transaction.
     * <p>
     * The Realm reverts back to read-only.
     * <p>
     * Calling this when not in a transaction will throw an exception.
     */
    public void cancelTransaction() {
        checkIfValid();
        sharedRealm.cancelTransaction();
    }

    /**
     * Checks if a Realm's underlying resources are still available or not getting accessed from the wrong thread.
     */
    protected void checkIfValid() {
        if (sharedRealm == null || sharedRealm.isClosed()) {
            throw new IllegalStateException(BaseRealm.CLOSED_REALM_MESSAGE);
        }

        // Check if we are in the right thread
        if (threadId != Thread.currentThread().getId()) {
            throw new IllegalStateException(BaseRealm.INCORRECT_THREAD_MESSAGE);
        }
    }

    protected void checkIfInTransaction() {
        if (!sharedRealm.isInTransaction()) {
            throw new IllegalStateException("Changing Realm data can only be done from inside a transaction.");
        }
    }

    /**
     * Check if the Realm is valid and in a transaction.
     */
    protected void checkIfValidAndInTransaction() {
        if (!isInTransaction()) {
            throw new IllegalStateException(NOT_IN_TRANSACTION_MESSAGE);
        }
    }

    /**
     * Returns the canonical path to where this Realm is persisted on disk.
     *
     * @return the canonical path to the Realm file.
     * @see File#getCanonicalPath()
     */
    public String getPath() {
        return configuration.getPath();
    }

    /**
     * Returns the {@link RealmConfiguration} for this Realm.
     *
     * @return the {@link RealmConfiguration} for this Realm.
     */
    public RealmConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Returns the schema version for this Realm.
     *
     * @return the schema version for the Realm file backing this Realm.
     */
    public long getVersion() {
        return sharedRealm.getSchemaVersion();
    }

    /**
     * Closes the Realm instance and all its resources.
     * <p>
     * It's important to always remember to close Realm instances when you're done with it in order not to leak memory,
     * file descriptors or grow the size of Realm file out of measure.
     *
     * @throws IllegalStateException if attempting to close from another thread.
     */
    @Override
    public void close() {
        if (this.threadId != Thread.currentThread().getId()) {
            throw new IllegalStateException(INCORRECT_THREAD_CLOSE_MESSAGE);
        }

        RealmCache.release(this);
    }

    /**
     * Closes the Realm instances and all its resources without checking the {@link RealmCache}.
     */
    void doClose() {
        if (sharedRealm != null) {
            sharedRealm.close();
            sharedRealm = null;
        }
    }

    /**
     * Checks if the {@link io.realm.Realm} instance has already been closed.
     *
     * @return {@code true} if closed, {@code false} otherwise.
     * @throws IllegalStateException if attempting to close from another thread.
     */
    public boolean isClosed() {
        if (this.threadId != Thread.currentThread().getId()) {
            throw new IllegalStateException(INCORRECT_THREAD_MESSAGE);
        }

        return sharedRealm == null || sharedRealm.isClosed();
    }

    /**
     * Checks if this {@link io.realm.Realm} contains any objects.
     *
     * @return {@code true} if empty, @{code false} otherwise.
     */
    public boolean isEmpty() {
        checkIfValid();
        return sharedRealm.isEmpty();
    }

    // package protected so unit tests can access it
    void setVersion(long version) {
        Table metadataTable = sharedRealm.getTable(Table.METADATA_TABLE_NAME);
        if (metadataTable.getColumnCount() == 0) {
            metadataTable.addColumn(RealmFieldType.INTEGER, "version");
            metadataTable.addEmptyRow();
        }
        metadataTable.setLong(0, 0, version);
    }

    /**
     * Returns the schema for this Realm.
     *
     * @return The {@link RealmSchema} for this Realm.
     */
    public RealmSchema getSchema() {
        return schema;
    }

    <E extends RealmModel> E get(Class<E> clazz, long rowIndex) {
        Table table = schema.getTable(clazz);
        UncheckedRow row = table.getUncheckedRow(rowIndex);
        final BaseRealm.RealmObjectContext objectContext = BaseRealm.objectContext.get();
        try {
            objectContext.set(this, row, schema.getColumnInfo(clazz));
            E result = configuration.getSchemaMediator().newInstance(clazz);
            RealmObjectProxy proxy = (RealmObjectProxy) result;
            proxy.realmGet$proxyState().setTableVersion$realm();
            return result;
        } finally {
            objectContext.clear();
        }
    }

    // Used by RealmList/RealmResults
    // Invariant: if dynamicClassName != null -> clazz == DynamicRealmObject
    <E extends RealmModel> E get(Class<E> clazz, String dynamicClassName, long rowIndex) {
        final Table table = (dynamicClassName != null) ? schema.getTable(dynamicClassName) : schema.getTable(clazz);

        final BaseRealm.RealmObjectContext objectContext = BaseRealm.objectContext.get();
        try {
            E result;
            if (dynamicClassName != null) {
                @SuppressWarnings("unchecked")
                E dynamicObj = (E) new DynamicRealmObject(this,
                        (rowIndex != Table.NO_MATCH) ? table.getUncheckedRow(rowIndex) : InvalidRow.INSTANCE,
                        false);
                result = dynamicObj;
            } else {
                objectContext.set(this,
                        (rowIndex != Table.NO_MATCH) ? table.getUncheckedRow(rowIndex) : InvalidRow.INSTANCE,
                        schema.getColumnInfo(clazz));
                result = configuration.getSchemaMediator().newInstance(clazz);
            }

            RealmObjectProxy proxy = (RealmObjectProxy) result;
            if (rowIndex != Table.NO_MATCH) {
                proxy.realmGet$proxyState().setTableVersion$realm();
            }

            return result;
        } finally {
            objectContext.clear();
        }
    }

    /**
     * Deletes all objects from this Realm.
     *
     * @throws IllegalStateException if the corresponding Realm is closed or called from an incorrect thread.
     */
    public void deleteAll() {
        checkIfValid();
        for (RealmObjectSchema objectSchema : schema.getAll()) {
            schema.getTable(objectSchema.getClassName()).clear();
        }
    }

    static private boolean deletes(String canonicalPath, File rootFolder, String realmFileName) {
        final AtomicBoolean realmDeleted = new AtomicBoolean(true);

        List<File> filesToDelete = Arrays.asList(
                new File(rootFolder, realmFileName),
                new File(rootFolder, realmFileName + ".lock"),
                // Old core log file naming styles
                new File(rootFolder, realmFileName + ".log_a"),
                new File(rootFolder, realmFileName + ".log_b"),
                new File(rootFolder, realmFileName + ".log"),
                new File(canonicalPath));
        for (File fileToDelete : filesToDelete) {
            if (fileToDelete.exists()) {
                boolean deleteResult = fileToDelete.delete();
                if (!deleteResult) {
                    realmDeleted.set(false);
                    RealmLog.warn("Could not delete the file %s", fileToDelete);
                }
            }
        }
        return realmDeleted.get();
    }

    /**
     * Deletes the Realm file defined by the given configuration.
     */
    static boolean deleteRealm(final RealmConfiguration configuration) {
        final String management = ".management";
        final AtomicBoolean realmDeleted = new AtomicBoolean(true);

        RealmCache.invokeWithGlobalRefCount(configuration, new RealmCache.Callback() {
            @Override
            public void onResult(int count) {
                if (count != 0) {
                    throw new IllegalStateException("It's not allowed to delete the file associated with an open Realm. " +
                            "Remember to close() all the instances of the Realm before deleting its file: " + configuration.getPath());
                }

                String canonicalPath = configuration.getPath();
                File realmFolder = configuration.getRealmDirectory();
                String realmFileName = configuration.getRealmFileName();
                File managementFolder = new File(realmFolder, realmFileName + management);

                // delete files in management directory and the directory
                // there is no subfolders in the management directory
                File[] files = managementFolder.listFiles();
                if (files != null) {
                    for (File file : files) {
                        realmDeleted.set(realmDeleted.get() && file.delete());
                    }
                }
                realmDeleted.set(realmDeleted.get() && managementFolder.delete());

                // delete specific files in root directory
                realmDeleted.set(realmDeleted.get() && deletes(canonicalPath, realmFolder, realmFileName));
            }
        });

        return realmDeleted.get();
    }

    /**
     * Compacts the Realm file defined by the given configuration.
     *
     * @param configuration configuration for the Realm to compact.
     * @throws IllegalArgumentException if Realm is encrypted.
     * @return {@code true} if compaction succeeded, {@code false} otherwise.
     */
    static boolean compactRealm(final RealmConfiguration configuration) {
        // FIXME: Move this check to OS?
        if (configuration.getEncryptionKey() != null) {
            throw new IllegalArgumentException("Cannot currently compact an encrypted Realm.");
        }
        SharedRealm sharedRealm = SharedRealm.getInstance(configuration);
        Boolean result = sharedRealm.compact();
        sharedRealm.close();
        return result;
    }

    /**
     * Migrates the Realm file defined by the given configuration using the provided migration block.
     *
     * @param configuration configuration for the Realm that should be migrated.
     * @param migration if set, this migration block will override what is set in {@link RealmConfiguration}.
     * @param callback callback for specific Realm type behaviors.
     * @throws FileNotFoundException if the Realm file doesn't exist.
     */
    protected static void migrateRealm(final RealmConfiguration configuration, final RealmMigration migration,
                                       final MigrationCallback callback) throws FileNotFoundException {
        if (configuration == null) {
            throw new IllegalArgumentException("RealmConfiguration must be provided");
        }
        if (migration == null && configuration.getMigration() == null) {
            throw new RealmMigrationNeededException(configuration.getPath(), "RealmMigration must be provided");
        }

        final AtomicBoolean fileNotFound = new AtomicBoolean(false);

        RealmCache.invokeWithGlobalRefCount(configuration, new RealmCache.Callback() {
            @Override
            public void onResult(int count) {
                if (count != 0) {
                    throw new IllegalStateException("Cannot migrate a Realm file that is already open: "
                            + configuration.getPath());
                }

                File realmFile = new File(configuration.getPath());
                if (!realmFile.exists()) {
                    fileNotFound.set(true);
                    return;
                }

                RealmMigration realmMigration = (migration == null) ? configuration.getMigration() : migration;
                DynamicRealm realm = null;
                try {
                    realm = DynamicRealm.getInstance(configuration);
                    realm.beginTransaction();
                    long currentVersion = realm.getVersion();
                    realmMigration.migrate(realm, currentVersion, configuration.getSchemaVersion());
                    realm.setVersion(configuration.getSchemaVersion());
                    realm.commitTransaction();
                } catch (RuntimeException e) {
                    if (realm != null) {
                        realm.cancelTransaction();
                    }
                    throw e;
                } finally {
                    if (realm != null) {
                        realm.close();
                        callback.migrationComplete();
                    }
                }
            }
        });

        if (fileNotFound.get()) {
            throw new FileNotFoundException("Cannot migrate a Realm file which doesn't exist: "
                    + configuration.getPath());
        }
    }

    // Return true if this Realm can receive notifications.
    boolean hasValidNotifier() {
        return sharedRealm.realmNotifier != null && sharedRealm.realmNotifier.isValid();
    }

    @Override
    protected void finalize() throws Throwable {
        if (sharedRealm != null && !sharedRealm.isClosed()) {
            RealmLog.warn("Remember to call close() on all Realm instances. " +
                    "Realm %s is being finalized without being closed, " +
                    "this can lead to running out of native memory.", configuration.getPath()
            );
        }
        super.finalize();
    }

    // Internal delegate for migrations
    protected interface MigrationCallback {
        void migrationComplete();
    }

    public static final class RealmObjectContext {
        private BaseRealm realm;
        private Row row;
        private ColumnInfo columnInfo;

        public void set(BaseRealm realm, Row row, ColumnInfo columnInfo) {
            this.realm = realm;
            this.row = row;
            this.columnInfo = columnInfo;
        }

        public BaseRealm getRealm() {
            return realm;
        }

        public Row getRow() {
            return row;
        }

        public ColumnInfo getColumnInfo() {
            return columnInfo;
        }

        public void clear() {
            realm = null;
            row = null;
            columnInfo = null;
        }
    }
    static final class ThreadLocalRealmObjectContext extends ThreadLocal<RealmObjectContext> {
        @Override
        protected RealmObjectContext initialValue() {
            return new RealmObjectContext();
        }
    }

    public static final ThreadLocalRealmObjectContext objectContext = new ThreadLocalRealmObjectContext();
}

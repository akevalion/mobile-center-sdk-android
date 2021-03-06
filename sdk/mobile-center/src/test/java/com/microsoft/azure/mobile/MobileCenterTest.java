package com.microsoft.azure.mobile;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.microsoft.azure.mobile.channel.Channel;
import com.microsoft.azure.mobile.channel.DefaultChannel;
import com.microsoft.azure.mobile.ingestion.models.WrapperSdk;
import com.microsoft.azure.mobile.ingestion.models.json.LogFactory;
import com.microsoft.azure.mobile.utils.DeviceInfoHelper;
import com.microsoft.azure.mobile.utils.IdHelper;
import com.microsoft.azure.mobile.utils.MobileCenterLog;
import com.microsoft.azure.mobile.utils.storage.StorageHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static com.microsoft.azure.mobile.persistence.DatabasePersistenceAsync.THREAD_NAME;
import static com.microsoft.azure.mobile.utils.PrefStorageConstants.KEY_ENABLED;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@SuppressWarnings("unused")
@PrepareForTest({MobileCenter.class, Channel.class, Constants.class, MobileCenterLog.class, StorageHelper.class, StorageHelper.PreferencesStorage.class, IdHelper.class, StorageHelper.DatabaseStorage.class, DeviceInfoHelper.class})
public class MobileCenterTest {

    private static final String DUMMY_APP_SECRET = "123e4567-e89b-12d3-a456-426655440000";

    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    @Mock
    private Iterator<ContentValues> mDataBaseScannerIterator;

    private Application application;

    @Before
    public void setUp() {
        MobileCenter.unsetInstance();
        DummyService.sharedInstance = null;
        AnotherDummyService.sharedInstance = null;

        application = mock(Application.class);
        when(application.getApplicationContext()).thenReturn(application);

        mockStatic(Constants.class);
        mockStatic(MobileCenterLog.class);
        mockStatic(StorageHelper.class);
        mockStatic(StorageHelper.PreferencesStorage.class);
        mockStatic(IdHelper.class);
        mockStatic(StorageHelper.DatabaseStorage.class);

        /* First call to com.microsoft.azure.mobile.MobileCenter.isEnabled shall return true, initial state. */
        when(StorageHelper.PreferencesStorage.getBoolean(anyString(), eq(true))).thenReturn(true);

        /* Then simulate further changes to state. */
        PowerMockito.doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {

                /* Whenever the new state is persisted, make further calls return the new state. */
                String key = (String) invocation.getArguments()[0];
                boolean enabled = (Boolean) invocation.getArguments()[1];
                when(StorageHelper.PreferencesStorage.getBoolean(key, true)).thenReturn(enabled);
                return null;
            }
        }).when(StorageHelper.PreferencesStorage.class);
        StorageHelper.PreferencesStorage.putBoolean(anyString(), anyBoolean());

        /* Mock empty database. */
        StorageHelper.DatabaseStorage databaseStorage = mock(StorageHelper.DatabaseStorage.class);
        when(StorageHelper.DatabaseStorage.getDatabaseStorage(anyString(), anyString(), anyInt(), any(ContentValues.class), anyInt(), any(StorageHelper.DatabaseStorage.DatabaseErrorListener.class))).thenReturn(databaseStorage);
        StorageHelper.DatabaseStorage.DatabaseScanner databaseScanner = mock(StorageHelper.DatabaseStorage.DatabaseScanner.class);
        when(databaseStorage.getScanner(anyString(), anyObject())).thenReturn(databaseScanner);
        when(databaseScanner.iterator()).thenReturn(mDataBaseScannerIterator);
    }

    @After
    public void tearDown() {
        Constants.APPLICATION_DEBUGGABLE = false;
    }

    @Test
    public void singleton() {
        assertNotNull(MobileCenter.getInstance());
        assertSame(MobileCenter.getInstance(), MobileCenter.getInstance());
    }

    @Test
    public void nullVarargClass() {
        MobileCenter.start(application, DUMMY_APP_SECRET, (Class<? extends MobileCenterService>) null);

        /* Verify that no services have been auto-loaded since none are configured for this */
        assertTrue(MobileCenter.isConfigured());
        assertEquals(0, MobileCenter.getInstance().getServices().size());
        assertEquals(application, MobileCenter.getInstance().getApplication());
    }

    @Test
    public void nullVarargArray() {
        //noinspection ConfusingArgumentToVarargsMethod
        MobileCenter.start(application, DUMMY_APP_SECRET, (Class<? extends MobileCenterService>[]) null);
        MobileCenter.start((Class<? extends MobileCenterService>) null);
        //noinspection ConfusingArgumentToVarargsMethod
        MobileCenter.start((Class<? extends MobileCenterService>[]) null);

        /* Verify that no services have been auto-loaded since none are configured for this */
        assertTrue(MobileCenter.isConfigured());
        assertEquals(0, MobileCenter.getInstance().getServices().size());
        assertEquals(application, MobileCenter.getInstance().getApplication());
    }

    @Test
    public void startServiceBeforeConfigure() {
        MobileCenter.start(DummyService.class);
        assertFalse(MobileCenter.isConfigured());
        assertNull(MobileCenter.getInstance().getServices());
    }

    @Test
    public void useDummyServiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onChannelReady(any(Context.class), notNull(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
    }

    @Test
    public void useDummyServiceTestSplitCall() {
        assertFalse(MobileCenter.isConfigured());
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        assertTrue(MobileCenter.isConfigured());
        MobileCenter.start(DummyService.class);

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onChannelReady(any(Context.class), notNull(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
    }

    @Test
    public void configureAndStartTwiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        MobileCenter.start(application, DUMMY_APP_SECRET, AnotherDummyService.class); //ignored

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onChannelReady(any(Context.class), notNull(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
    }

    @Test
    public void configureTwiceTest() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.configure(application, DUMMY_APP_SECRET); //ignored
        MobileCenter.start(DummyService.class);

        /* Verify that single service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onChannelReady(any(Context.class), notNull(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
    }


    @Test
    public void startTwoServicesTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
    }

    @Test
    public void startTwoServicesSplit() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(DummyService.class, AnotherDummyService.class);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
    }

    @Test
    public void startTwoServicesSplitEvenMore() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(DummyService.class);
        MobileCenter.start(AnotherDummyService.class);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
    }

    @Test
    public void startTwoServicesWithSomeInvalidReferences() {
        MobileCenter.start(application, DUMMY_APP_SECRET, null, DummyService.class, null, InvalidService.class, AnotherDummyService.class, null);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
    }

    @Test
    public void startTwoServicesWithSomeInvalidReferencesSplit() {
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(null, DummyService.class, null);
        MobileCenter.start(InvalidService.class, AnotherDummyService.class, null);

        /* Verify that the right amount of services have been loaded and configured */
        assertEquals(2, MobileCenter.getInstance().getServices().size());
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(DummyService.getInstance()));
            verify(DummyService.getInstance()).getLogFactories();
            verify(DummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(DummyService.getInstance());
        }
        {
            assertTrue(MobileCenter.getInstance().getServices().contains(AnotherDummyService.getInstance()));
            verify(AnotherDummyService.getInstance()).getLogFactories();
            verify(AnotherDummyService.getInstance()).onChannelReady(any(Context.class), notNull(Channel.class));
            verify(application).registerActivityLifecycleCallbacks(AnotherDummyService.getInstance());
        }
    }

    @Test
    public void startServiceTwice() {

        /* Start once. */
        MobileCenter.configure(application, DUMMY_APP_SECRET);
        MobileCenter.start(DummyService.class);

        /* Check. */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        DummyService service = DummyService.getInstance();
        assertTrue(MobileCenter.getInstance().getServices().contains(service));
        verify(service).getLogFactories();
        verify(service).onChannelReady(any(Context.class), notNull(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);

        /* Start twice, this call is ignored. */
        MobileCenter.start(DummyService.class);

        /* Verify that single service has been loaded and configured (only once interaction). */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
        verify(service).getLogFactories();
        verify(service).onChannelReady(any(Context.class), notNull(Channel.class));
        verify(application).registerActivityLifecycleCallbacks(service);
    }

    @Test
    public void enableTest() throws Exception {

        /* Mock handler for asynchronous persistence */
        HandlerThread mockHandlerThread = PowerMockito.mock(HandlerThread.class);
        Looper mockLooper = PowerMockito.mock(Looper.class);
        whenNew(HandlerThread.class).withArguments(THREAD_NAME).thenReturn(mockHandlerThread);
        when(mockHandlerThread.getLooper()).thenReturn(mockLooper);
        Handler mockPersistenceHandler = PowerMockito.mock(Handler.class);
        whenNew(Handler.class).withArguments(mockLooper).thenReturn(mockPersistenceHandler);
        when(mockPersistenceHandler.post(any(Runnable.class))).then(new Answer<Boolean>() {

            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                ((Runnable) invocation.getArguments()[0]).run();
                return true;
            }
        });

        /* Start MobileCenter SDK */
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);
        Channel channel = mock(Channel.class);
        MobileCenter mobileCenter = MobileCenter.getInstance();
        mobileCenter.setChannel(channel);

        /* Verify services are enabled by default */
        Set<MobileCenterService> services = mobileCenter.getServices();
        assertTrue(MobileCenter.isEnabled());
        DummyService dummyService = DummyService.getInstance();
        AnotherDummyService anotherDummyService = AnotherDummyService.getInstance();
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }

        /* Explicit set enabled should not change that */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }
        verify(dummyService, never()).setInstanceEnabled(anyBoolean());
        verify(anotherDummyService, never()).setInstanceEnabled(anyBoolean());
        verify(channel).setEnabled(true);

        /* Verify disabling base disables all services */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertFalse(service.isInstanceEnabled());
        }
        verify(dummyService).setInstanceEnabled(false);
        verify(anotherDummyService).setInstanceEnabled(false);
        verify(application).unregisterActivityLifecycleCallbacks(dummyService);
        verify(application).unregisterActivityLifecycleCallbacks(anotherDummyService);
        verify(channel).setEnabled(false);

        /* Verify re-enabling base re-enables all services */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }
        verify(dummyService).setInstanceEnabled(true);
        verify(anotherDummyService).setInstanceEnabled(true);
        verify(application, times(2)).registerActivityLifecycleCallbacks(dummyService);
        verify(application, times(2)).registerActivityLifecycleCallbacks(anotherDummyService);
        verify(channel, times(2)).setEnabled(true);

        /* Verify that disabling one service leaves base and other services enabled */
        dummyService.setInstanceEnabled(false);
        assertFalse(dummyService.isInstanceEnabled());
        assertTrue(MobileCenter.isEnabled());
        assertTrue(anotherDummyService.isInstanceEnabled());

        /* Enable back via main class. */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertTrue(service.isInstanceEnabled());
        }
        verify(dummyService, times(2)).setInstanceEnabled(true);
        verify(anotherDummyService).setInstanceEnabled(true);
        verify(channel, times(3)).setEnabled(true);

        /* Enable service after the SDK is disabled. */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertFalse(service.isInstanceEnabled());
        }
        dummyService.setInstanceEnabled(true);
        assertFalse(dummyService.isInstanceEnabled());
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
        assertFalse(MobileCenter.isEnabled());
        verify(channel, times(2)).setEnabled(false);

        /* Disable back via main class. */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : services) {
            assertFalse(service.isInstanceEnabled());
        }
        verify(channel, times(3)).setEnabled(false);

        /* Check factories / channel only once interactions. */
        verify(dummyService).getLogFactories();
        verify(dummyService).onChannelReady(any(Context.class), any(Channel.class));
        verify(anotherDummyService).getLogFactories();
        verify(anotherDummyService).onChannelReady(any(Context.class), any(Channel.class));
    }

    @Test
    public void enableBeforeConfiguredTest() {
        /* Test isEnabled and setEnabled before configure */
        assertFalse(MobileCenter.isEnabled());
        MobileCenter.setEnabled(true);
        assertFalse(MobileCenter.isEnabled());
        PowerMockito.verifyStatic(times(3));
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void disablePersisted() {
        when(StorageHelper.PreferencesStorage.getBoolean(KEY_ENABLED, true)).thenReturn(false);
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);
        Channel channel = mock(Channel.class);
        MobileCenter mobileCenter = MobileCenter.getInstance();
        mobileCenter.setChannel(channel);

        /* Verify services are enabled by default but MobileCenter is disabled. */
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertTrue(service.isInstanceEnabled());
            verify(application, never()).registerActivityLifecycleCallbacks(service);
        }

        /* Verify we can enable back. */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertTrue(service.isInstanceEnabled());
            verify(application).registerActivityLifecycleCallbacks(service);
            verify(application, never()).unregisterActivityLifecycleCallbacks(service);
        }
    }

    @Test
    public void disablePersistedAndDisable() {
        when(StorageHelper.PreferencesStorage.getBoolean(KEY_ENABLED, true)).thenReturn(false);
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, AnotherDummyService.class);
        Channel channel = mock(Channel.class);
        MobileCenter mobileCenter = MobileCenter.getInstance();
        mobileCenter.setChannel(channel);

        /* Its already disabled so disable should have no effect on MobileCenter but should disable services. */
        MobileCenter.setEnabled(false);
        assertFalse(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertFalse(service.isInstanceEnabled());
            verify(application, never()).registerActivityLifecycleCallbacks(service);
            verify(application, never()).unregisterActivityLifecycleCallbacks(service);
        }

        /* Verify we can enable MobileCenter back, should have no effect on service except registering the application life cycle callbacks. */
        MobileCenter.setEnabled(true);
        assertTrue(MobileCenter.isEnabled());
        for (MobileCenterService service : mobileCenter.getServices()) {
            assertTrue(service.isInstanceEnabled());
            verify(application).registerActivityLifecycleCallbacks(service);
            verify(application, never()).unregisterActivityLifecycleCallbacks(service);
        }
    }

    @Test
    public void invalidServiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, InvalidService.class);
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString(), any(NoSuchMethodException.class));
    }

    @Test
    public void nullApplicationTest() {
        MobileCenter.start(null, DUMMY_APP_SECRET, DummyService.class);
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void nullAppIdentifierTest() {
        MobileCenter.start(application, null, DummyService.class);
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void emptyAppIdentifierTest() {
        MobileCenter.start(application, "", DummyService.class);
        PowerMockito.verifyStatic();
        MobileCenterLog.error(eq(MobileCenter.LOG_TAG), anyString());
    }

    @Test
    public void duplicateServiceTest() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class, DummyService.class);

        /* Verify that only one service has been loaded and configured */
        assertEquals(1, MobileCenter.getInstance().getServices().size());
    }

    @Test
    public void getInstallIdBeforeStart() {
        assertNull(MobileCenter.getInstallId());
    }

    @Test
    public void setWrapperSdkTest() throws Exception {

        /* Setup  mocking. */
        DefaultChannel channel = mock(DefaultChannel.class);
        whenNew(DefaultChannel.class).withAnyArguments().thenReturn(channel);
        mockStatic(DeviceInfoHelper.class);

        /* Call method. */
        WrapperSdk wrapperSdk = new WrapperSdk();
        MobileCenter.setWrapperSdk(wrapperSdk);

        /* Check propagation. */
        verifyStatic();
        DeviceInfoHelper.setWrapperSdk(wrapperSdk);

        /* Since the channel was not created when setting wrapper, no need to refresh channel after start. */
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verify(channel, never()).invalidateDeviceCache();

        /* Update wrapper SDK and check channel refreshed. */
        wrapperSdk = new WrapperSdk();
        MobileCenter.setWrapperSdk(wrapperSdk);
        verify(channel).invalidateDeviceCache();
    }


    @Test
    public void setDefaultLogLevelRelease() {
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verifyStatic(never());
        MobileCenterLog.setLogLevel(anyInt());
    }

    @Test
    public void setDefaultLogLevelDebug() {
        Constants.APPLICATION_DEBUGGABLE = true;
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verifyStatic();
        MobileCenterLog.setLogLevel(android.util.Log.WARN);
    }

    @Test
    public void dontSetDefaultLogLevel() {
        Constants.APPLICATION_DEBUGGABLE = true;
        MobileCenter.setLogLevel(android.util.Log.VERBOSE);
        verifyStatic();
        MobileCenterLog.setLogLevel(android.util.Log.VERBOSE);
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verifyStatic(never());
        MobileCenterLog.setLogLevel(android.util.Log.WARN);
    }

    @Test
    public void setServerUrl() throws Exception {

        /* Change server URL before start. */
        DefaultChannel channel = mock(DefaultChannel.class);
        whenNew(DefaultChannel.class).withAnyArguments().thenReturn(channel);
        String serverUrl = "http://mock";
        MobileCenter.setServerUrl(serverUrl);

        /* No effect for now. */
        verify(channel, never()).setServerUrl(serverUrl);

        /* Start should propagate the server url. */
        MobileCenter.start(application, DUMMY_APP_SECRET, DummyService.class);
        verify(channel).setServerUrl(serverUrl);

        /* Change it after, should work immediately. */
        serverUrl = "http://mock2";
        MobileCenter.setServerUrl(serverUrl);
        verify(channel).setServerUrl(serverUrl);
    }

    private static class DummyService extends AbstractMobileCenterService {

        private static DummyService sharedInstance;

        @SuppressWarnings("WeakerAccess")
        public static DummyService getInstance() {
            if (sharedInstance == null) {
                sharedInstance = spy(new DummyService());
            }
            return sharedInstance;
        }

        @Override
        protected String getGroupName() {
            return "group_dummy";
        }

        @Override
        protected String getServiceName() {
            return "Dummy";
        }

        @Override
        protected String getLoggerTag() {
            return "DummyLog";
        }
    }

    private static class AnotherDummyService extends AbstractMobileCenterService {

        private static AnotherDummyService sharedInstance;

        @SuppressWarnings("WeakerAccess")
        public static AnotherDummyService getInstance() {
            if (sharedInstance == null) {
                sharedInstance = spy(new AnotherDummyService());
            }
            return sharedInstance;
        }

        @Override
        public Map<String, LogFactory> getLogFactories() {
            HashMap<String, LogFactory> logFactories = new HashMap<>();
            logFactories.put("mock", mock(LogFactory.class));
            return logFactories;
        }

        @Override
        protected String getGroupName() {
            return "group_another_dummy";
        }

        @Override
        protected String getServiceName() {
            return "AnotherDummy";
        }

        @Override
        protected String getLoggerTag() {
            return "AnotherDummyLog";
        }
    }

    private static class InvalidService extends AbstractMobileCenterService {

        @Override
        protected String getGroupName() {
            return "group_invalid";
        }

        @Override
        protected String getServiceName() {
            return "Invalid";
        }

        @Override
        protected String getLoggerTag() {
            return "InvalidLog";
        }
    }
}

package com.microsoft.azure.mobile.crashes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.support.test.InstrumentationRegistry;

import com.microsoft.azure.mobile.Constants;
import com.microsoft.azure.mobile.ResultCallback;
import com.microsoft.azure.mobile.channel.Channel;
import com.microsoft.azure.mobile.crashes.ingestion.models.ManagedErrorLog;
import com.microsoft.azure.mobile.crashes.model.ErrorReport;
import com.microsoft.azure.mobile.crashes.utils.ErrorLogHelper;
import com.microsoft.azure.mobile.ingestion.models.Log;
import com.microsoft.azure.mobile.utils.HandlerUtils;
import com.microsoft.azure.mobile.utils.MobileCenterLog;
import com.microsoft.azure.mobile.utils.storage.StorageHelper;

import junit.framework.AssertionFailedError;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static com.microsoft.azure.mobile.test.TestUtils.TAG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unused")
public class CrashesAndroidTest {

    @SuppressLint("StaticFieldLeak")
    private static Context sContext;

    private static Thread.UncaughtExceptionHandler sDefaultCrashHandler;

    @BeforeClass
    public static void setUpClass() {
        MobileCenterLog.setLogLevel(android.util.Log.VERBOSE);
        sContext = InstrumentationRegistry.getContext();
        Constants.loadFromContext(sContext);
        StorageHelper.initialize(sContext);
        sDefaultCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Before
    public void cleanup() {
        android.util.Log.i(TAG, "Cleanup");
        Crashes.unsetInstance();
        Thread.setDefaultUncaughtExceptionHandler(sDefaultCrashHandler);
        StorageHelper.PreferencesStorage.clear();
        for (File logFile : ErrorLogHelper.getErrorStorageDirectory().listFiles()) {
            assertTrue(logFile.delete());
        }
    }

    private void waitForCrashesHandlerTasksToComplete() throws InterruptedException {
        final Semaphore semaphore = new Semaphore(0);

        /* Waiting background thread for initialize and processPendingErrors. */
        Crashes.getInstance().mHandler.post(new Runnable() {

            @Override
            public void run() {
                semaphore.release();
            }
        });
        semaphore.acquire();

        /* Waiting main thread for processUserConfirmation. */
        HandlerUtils.runOnUiThread(new Runnable() {

            @Override
            public void run() {
                semaphore.release();
            }
        });
        semaphore.acquire();
    }

    @Test
    public void getLastSessionCrashReport() throws InterruptedException {

        /* Crash on 1st process. */
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        Channel channel = mock(Channel.class);
        Crashes.getInstance().onChannelReady(sContext, channel);
        final Error exception = generateStackOverflowError();
        assertTrue(exception.getStackTrace().length > ErrorLogHelper.FRAME_LIMIT);
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();

        /* Get last session crash on 2nd process. */
        Crashes.unsetInstance();
        Crashes.getInstance().mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new AssertionFailedError();
                }
            }
        });
        Crashes.getInstance().onChannelReady(sContext, channel);
        assertNotNull(Crashes.getLastSessionCrashReport());

        /* Try to get last session crash after Crashes service completed processing. */
        waitForCrashesHandlerTasksToComplete();
        assertNotNull(Crashes.getLastSessionCrashReport());
    }

    @Test
    public void testNoDuplicateCallbacksOrSending() throws InterruptedException {

        /* Crash on 1st process. */
        assertFalse(Crashes.hasCrashedInLastSession());
        android.util.Log.i(TAG, "Process 1");
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        Channel channel = mock(Channel.class);
        Crashes.getInstance().onChannelReady(sContext, channel);
        CrashesListener crashesListener = mock(CrashesListener.class);
        when(crashesListener.shouldProcess(any(ErrorReport.class))).thenReturn(true);
        when(crashesListener.shouldAwaitUserConfirmation()).thenReturn(true);
        Crashes.setListener(crashesListener);
        final Error exception = generateStackOverflowError();
        assertTrue(exception.getStackTrace().length > ErrorLogHelper.FRAME_LIMIT);
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();
        assertEquals(ErrorLogHelper.FRAME_LIMIT, exception.getStackTrace().length);
        verify(uncaughtExceptionHandler).uncaughtException(thread, exception);
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles().length);
        verifyZeroInteractions(crashesListener);

        /* Second process: enqueue log but network is down... */
        android.util.Log.i(TAG, "Process 2");
        final AtomicReference<Log> log = new AtomicReference<>();
        doAnswer(new Answer() {

            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                log.set((Log) invocationOnMock.getArguments()[0]);
                return null;
            }
        }).when(channel).enqueue(any(Log.class), anyString());
        Crashes.unsetInstance();
        Crashes.setListener(crashesListener);
        Crashes.getInstance().onChannelReady(sContext, channel);
        waitForCrashesHandlerTasksToComplete();

        /* Check last session error report. */
        assertTrue(Crashes.hasCrashedInLastSession());
        Crashes.getLastSessionCrashReportAsync(new ResultCallback<ErrorReport>() {

            @Override
            public void onResult(ErrorReport errorReport) {
                assertNotNull(errorReport);
                Throwable lastThrowable = errorReport.getThrowable();
                assertTrue(lastThrowable instanceof StackOverflowError);
                assertEquals(ErrorLogHelper.FRAME_LIMIT, lastThrowable.getStackTrace().length);
            }
        });

        /* Waiting user confirmation so no log sent yet. */
        verify(channel, never()).enqueue(any(Log.class), anyString());
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles().length);
        verify(crashesListener).shouldProcess(any(ErrorReport.class));
        verify(crashesListener).shouldAwaitUserConfirmation();
        verifyNoMoreInteractions(crashesListener);

        /* Confirm to resume processing. */
        Crashes.notifyUserConfirmation(Crashes.ALWAYS_SEND);
        verify(channel).enqueue(any(Log.class), anyString());
        assertNotNull(log.get());
        assertEquals(1, ErrorLogHelper.getErrorStorageDirectory().listFiles().length);
        verify(crashesListener).getErrorAttachment(any(ErrorReport.class));
        verifyNoMoreInteractions(crashesListener);

        /* Third process: sending succeeds. */
        android.util.Log.i(TAG, "Process 3");
        final AtomicReference<Channel.GroupListener> groupListener = new AtomicReference<>();
        channel = mock(Channel.class);
        doAnswer(new Answer() {

            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Channel.GroupListener listener = (Channel.GroupListener) invocationOnMock.getArguments()[4];
                groupListener.set(listener);
                listener.onBeforeSending(log.get());
                return null;
            }
        }).when(channel).addGroup(anyString(), anyInt(), anyInt(), anyInt(), any(Channel.GroupListener.class));
        Crashes.unsetInstance();
        Crashes.setListener(crashesListener);
        Crashes.getInstance().onChannelReady(sContext, channel);
        waitForCrashesHandlerTasksToComplete();
        assertFalse(Crashes.hasCrashedInLastSession());
        Crashes.getLastSessionCrashReportAsync(new ResultCallback<ErrorReport>() {

            @Override
            public void onResult(ErrorReport errorReport) {
                assertNull(errorReport);
            }
        });

        assertNotNull(groupListener.get());
        groupListener.get().onSuccess(log.get());
        assertEquals(0, ErrorLogHelper.getErrorStorageDirectory().listFiles().length);
        verify(channel, never()).enqueue(any(Log.class), anyString());
        verify(crashesListener).onBeforeSending(any(ErrorReport.class));
        verify(crashesListener).onSendingSucceeded(any(ErrorReport.class));
        verifyNoMoreInteractions(crashesListener);

        /* Verify log was truncated to 256 frames. */
        assertTrue(log.get() instanceof ManagedErrorLog);
        ManagedErrorLog errorLog = (ManagedErrorLog) log.get();
        assertNotNull(errorLog.getException());
        assertNotNull(errorLog.getException().getFrames());
        assertEquals(ErrorLogHelper.FRAME_LIMIT, errorLog.getException().getFrames().size());
    }

    private Error generateStackOverflowError() {
        try {
            return generateStackOverflowError();
        } catch (StackOverflowError error) {
            return error;
        }
    }

    @Test
    public void cleanupFilesOnDisable() throws InterruptedException {

        /* Crash on 1st process. */
        android.util.Log.i(TAG, "Process 1");
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        Crashes.getInstance().onChannelReady(sContext, mock(Channel.class));
        final RuntimeException exception = new RuntimeException();
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();
        verify(uncaughtExceptionHandler).uncaughtException(thread, exception);
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles().length);

        /* Disable in process 2. */
        Crashes.unsetInstance();
        Crashes.setEnabled(false);
        assertEquals(0, ErrorLogHelper.getErrorStorageDirectory().listFiles().length);
    }

    @Test
    public void wrapperSdkOverrideLog() throws InterruptedException {

        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        Channel channel = mock(Channel.class);
        Crashes.getInstance().onChannelReady(sContext, channel);
        Crashes.WrapperSdkListener wrapperSdkListener = mock(Crashes.WrapperSdkListener.class);
        Crashes.getInstance().setWrapperSdkListener(wrapperSdkListener);
        doAnswer(new Answer() {

            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                ManagedErrorLog errorLog = (ManagedErrorLog) invocationOnMock.getArguments()[0];
                errorLog.setErrorThreadName("ReplacedErrorThreadName");
                WrapperSdkExceptionManager.saveWrapperSdkErrorLog(errorLog);
                return null;
            }
        }).when(wrapperSdkListener).onCrashCaptured(notNull(ManagedErrorLog.class));
        final RuntimeException exception = new RuntimeException("mock");
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();
        verify(wrapperSdkListener).onCrashCaptured(notNull(ManagedErrorLog.class));
        Crashes.unsetInstance();
        Crashes.getInstance().onChannelReady(sContext, channel);
        waitForCrashesHandlerTasksToComplete();
        Crashes.getLastSessionCrashReportAsync(new ResultCallback<ErrorReport>() {

            @Override
            public void onResult(ErrorReport errorReport) {
                assertNotNull(errorReport);
                assertEquals("ReplacedErrorThreadName", errorReport.getThreadName());
            }
        });
    }

    @Test
    public void setEnabledWhileAlreadyEnabledShouldNotDuplicateCrashReport() throws InterruptedException {

        /* Test the fix of the duplicate crash sending bug. */
        android.util.Log.i(TAG, "Process 1");
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        Channel channel = mock(Channel.class);
        Crashes.getInstance().onChannelReady(sContext, channel);
        Crashes.setEnabled(true);
        final RuntimeException exception = new RuntimeException();
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();
        verify(uncaughtExceptionHandler).uncaughtException(thread, exception);

        /* Check there are only 2 files: the throwable and the json one. */
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles().length);
    }
}

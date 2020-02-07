/*******************************************************************************
 * Copyright 2013-2019 QaProSoft (http://www.qaprosoft.com).
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
 *******************************************************************************/
package com.qaprosoft.carina.core.foundation.listeners;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.testng.IRetryAnalyzer;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.TestListenerAdapter;

import com.qaprosoft.carina.core.foundation.commons.SpecialKeywords;
import com.qaprosoft.carina.core.foundation.dataprovider.parser.DSBean;
import com.qaprosoft.carina.core.foundation.jira.Jira;
import com.qaprosoft.carina.core.foundation.report.Artifacts;
import com.qaprosoft.carina.core.foundation.report.ReportContext;
import com.qaprosoft.carina.core.foundation.report.TestResultItem;
import com.qaprosoft.carina.core.foundation.report.TestResultType;
import com.qaprosoft.carina.core.foundation.report.email.EmailReportItemCollector;
import com.qaprosoft.carina.core.foundation.retry.RetryAnalyzer;
import com.qaprosoft.carina.core.foundation.utils.DateUtils;
import com.qaprosoft.carina.core.foundation.utils.Messager;
import com.qaprosoft.carina.core.foundation.utils.ParameterGenerator;
import com.qaprosoft.carina.core.foundation.utils.R;
import com.qaprosoft.carina.core.foundation.utils.StringGenerator;
import com.qaprosoft.carina.core.foundation.utils.naming.TestNamingUtil;
import com.qaprosoft.carina.core.foundation.utils.video.VideoAnalyzer;
import com.qaprosoft.carina.core.foundation.webdriver.IDriverPool;
import com.qaprosoft.carina.core.foundation.webdriver.device.Device;

@SuppressWarnings("deprecation")
public class AbstractTestListener extends TestListenerAdapter implements IDriverPool {
    private static final Logger LOGGER = Logger.getLogger(AbstractTestListener.class);
    protected static ThreadLocal<TestResultItem> configFailures = new ThreadLocal<TestResultItem>();

    private void startItem(ITestResult result, Messager messager) {

        String test = TestNamingUtil.getCanonicalTestName(result);
        test = TestNamingUtil.associateTestInfo2Thread(test, Thread.currentThread().getId(), result);

        String deviceName = getDeviceName();
        messager.info(deviceName, test, DateUtils.now());
    }

    private void passItem(ITestResult result, Messager messager) {
        String test = TestNamingUtil.getCanonicalTestName(result);

        String deviceName = getDeviceName();

        messager.info(deviceName, test, DateUtils.now());

        EmailReportItemCollector
                .push(createTestResult(result, TestResultType.PASS, null, result.getMethod().getDescription()));
        result.getTestContext().removeAttribute(SpecialKeywords.TEST_FAILURE_MESSAGE);

    }

    private String failItem(ITestResult result, Messager messager) {
        String test = TestNamingUtil.getCanonicalTestName(result);

        String errorMessage = getFailureReason(result);
        String deviceName = getDeviceName();

        // TODO: remove hard-coded text
        if (!errorMessage.contains("All tests were skipped! Analyze logs to determine possible configuration issues.")) {
            messager.error(deviceName, test, DateUtils.now(), errorMessage);
            if (!R.EMAIL.getBoolean("fail_full_stacktrace_in_report") && result.getThrowable() != null
                    && result.getThrowable().getMessage() != null
                    && !StringUtils.isEmpty(result.getThrowable().getMessage())) {
                EmailReportItemCollector.push(createTestResult(result, TestResultType.FAIL,
                        result.getThrowable().getMessage(), result.getMethod().getDescription()));
            } else {
                EmailReportItemCollector.push(createTestResult(result, TestResultType.FAIL, errorMessage, result
                        .getMethod().getDescription()));
            }
        }

        result.getTestContext().removeAttribute(SpecialKeywords.TEST_FAILURE_MESSAGE);
        return errorMessage;
    }

    private String failRetryItem(ITestResult result, Messager messager, int count, int maxCount) {
        String test = TestNamingUtil.getCanonicalTestName(result);

        String errorMessage = getFailureReason(result);

        String deviceName = getDeviceName();

        messager.error(deviceName, test, String.valueOf(count), String.valueOf(maxCount), errorMessage);

        result.getTestContext().removeAttribute(SpecialKeywords.TEST_FAILURE_MESSAGE);
        return errorMessage;
    }

    private String skipItem(ITestResult result, Messager messager) {
        String test = TestNamingUtil.getCanonicalTestName(result);

        String errorMessage = getFailureReason(result);
        if (errorMessage.isEmpty()) {
            // identify is it due to the dependent failure or exception in before suite/class/method
            String[] methods = result.getMethod().getMethodsDependedUpon();

            // find if any parent method failed/skipped
            boolean dependentMethod = false;
            String dependentMethodName = "";
            for (ITestResult failedTest : result.getTestContext().getFailedTests().getAllResults()) {
                for (int i = 0; i < methods.length; i++) {
                    if (methods[i].contains(failedTest.getName())) {
                        dependentMethodName = failedTest.getName();
                        dependentMethod = true;
                        break;
                    }
                }
            }

            for (ITestResult skippedTest : result.getTestContext().getSkippedTests().getAllResults()) {
                for (int i = 0; i < methods.length; i++) {
                    if (methods[i].contains(skippedTest.getName())) {
                        dependentMethodName = skippedTest.getName();
                        dependentMethod = true;
                        break;
                    }
                }
            }

            if (dependentMethod) {
                errorMessage = "Test skipped due to the dependency from: " + dependentMethodName;
            } else {
                // Try to find error details from last configuration failure in this thread
                TestResultItem resultItem = getConfigFailure();
                if (resultItem != null) {
                    errorMessage = resultItem.getFailReason();
                }
            }
        }

        String deviceName = getDeviceName();

        messager.warn(deviceName, test, DateUtils.now(), errorMessage);

        EmailReportItemCollector
                .push(createTestResult(result, TestResultType.SKIP, errorMessage, result.getMethod().getDescription()));

        result.getTestContext().removeAttribute(SpecialKeywords.TEST_FAILURE_MESSAGE);
        return errorMessage;
    }

    private void skipAlreadyPassedItem(ITestResult result, Messager messager) {
        String test = TestNamingUtil.getCanonicalTestName(result);
        String deviceName = getDeviceName();
        messager.info(deviceName, test, DateUtils.now());
    }

    private String getDeviceName() {
        String deviceName = IDriverPool.getDefaultDevice().getName();
        String deviceUdid = IDriverPool.getDefaultDevice().getUdid();

        if (!deviceName.isEmpty() && !deviceUdid.isEmpty()) {
            deviceName = deviceName + " - " + deviceUdid;
        }

        return deviceName;
    }

    private void afterConfiguration(ITestResult result) {
        TestNamingUtil.releaseTestInfoByThread();
    }

    private void afterTest(ITestResult result) {
        // TODO: do not publish log/demo anymore
        //Artifacts.add("Logs", ReportContext.getTestLogLink(test));
        //Artifacts.add("Demo", ReportContext.getTestScreenshotsLink(test));
        
        // device log
        Device device = IDriverPool.getDefaultDevice();
        if (!device.isNull()) {
            LOGGER.debug("Device isn't null additional artifacts will be extracted.");
            File sysLogFile = device.saveSysLog();
            if (sysLogFile != null) {
                LOGGER.debug("Logcat log will be extracted and added as artifact");
                Artifacts.add("Logcat", ReportContext.getSysLogLink());
            }
        }
        
        ReportContext.generateTestReport();

        TestNamingUtil.releaseTestInfoByThread();
        ReportContext.emptyTestDirData();
    }

    @Override
    public void beforeConfiguration(ITestResult result) {
        // added 3 below lines to be able to track log/screenshots for before suite/class/method actions too
        TestNamingUtil.releaseTestInfoByThread();

        super.beforeConfiguration(result);
    }

    @Override
    public void onConfigurationSuccess(ITestResult result) {
        afterConfiguration(result);
        // passItem(result, Messager.CONFIG_PASSED);
        super.onConfigurationSuccess(result);
    }

    @Override
    public void onConfigurationSkip(ITestResult result) {
        afterConfiguration(result);
        // skipItem(result, Messager.CONFIG_SKIPPED);
        super.onConfigurationSkip(result);
    }

    @Override
    public void onConfigurationFailure(ITestResult result) {
        afterConfiguration(result);
        // failItem(result, Messager.CONFIG_FAILED);
        // String test = TestNamingUtil.getCanonicalTestName(result);
        // closeLogAppender(test);

        String errorMessage = getFailureReason(result);

        TestResultItem resultItem = createTestResult(result, TestResultType.FAIL, errorMessage,
                result.getMethod().getDescription());
        setConfigFailure(resultItem);

        super.onConfigurationFailure(result);
    }

    @Override
    public void onStart(ITestContext context) {
        String uuid = StringGenerator.generateNumeric(8);
        ParameterGenerator.setUUID(uuid);

        ReportContext.getBaseDir(); // create directory for logging as soon as possible

        super.onStart(context);
    }

    @Override
    public void onTestStart(ITestResult result) {
        VideoAnalyzer.disableVideoUpload();
        IRetryAnalyzer curRetryAnalyzer = result.getMethod().getRetryAnalyzer();
        if (curRetryAnalyzer == null) {
            // Declare carina custom RetryAnalyzer annotation for each new test method. Handle use-case for data providers which has single method!
            result.getMethod().setRetryAnalyzer(new RetryAnalyzer());
        } else {
            if (!(curRetryAnalyzer instanceof RetryAnalyzer)) {
                LOGGER.warn("Custom RetryAnalyzer is used: " + curRetryAnalyzer.getClass().getName());                
            }
            
        }
        
        generateParameters(result);

        if (!result.getTestContext().getCurrentXmlTest().getTestParameters()
                .containsKey(SpecialKeywords.EXCEL_DS_CUSTOM_PROVIDER) &&
                result.getParameters().length > 0) // set parameters from XLS only if test contains any parameter at
                                                   // all)
        {
            if (result.getTestContext().getCurrentXmlTest().getTestParameters()
                    .containsKey(SpecialKeywords.EXCEL_DS_ARGS)) {
                DSBean dsBean = new DSBean(result.getTestContext());
                int index = 0;
                for (String arg : dsBean.getArgs()) {
                    dsBean.getTestParams().put(arg, (String) result.getParameters()[index++]);
                }
                result.getTestContext().getCurrentXmlTest().setParameters(dsBean.getTestParams());

            }
        }
        // obligatory reset any registered canonical name because for ALREADY_PASSED methods we can't do this in
        // onTestSkipped method
        TestNamingUtil.releaseTestInfoByThread();

        startItem(result, Messager.TEST_STARTED);

    }
    
    private void generateParameters(ITestResult result) {
        if (result != null && result.getParameters() != null) {
            for (int i = 0; i < result.getParameters().length; i++) {
                if (result.getParameters()[i] instanceof String) {
                    result.getParameters()[i] = ParameterGenerator.process(result.getParameters()[i].toString());
                }

                if (result.getParameters()[i] instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> dynamicAgrs = (Map<String, String>) result.getParameters()[i];
                    for (Map.Entry<String, String> entry : dynamicAgrs.entrySet()) {
                        Object param = ParameterGenerator.process(entry.getValue());
                        if (param != null)
                            dynamicAgrs.put(entry.getKey(), param.toString());
                        else
                            dynamicAgrs.put(entry.getKey(), null);
                    }
                }
            }
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        passItem(result, Messager.TEST_PASSED);
        VideoAnalyzer.enableVideoUpload();

        afterTest(result);
        super.onTestSuccess(result);
        
        // resetCounter for current thread needed to support correctly data-provider reruns (multi-threading as well)
        RetryAnalyzer retryAnalyzer = (RetryAnalyzer) result.getMethod().getRetryAnalyzer();
        if (retryAnalyzer != null) {
            removeFailedRetryTests(result, retryAnalyzer.getRunCount());
            retryAnalyzer.resetCounter();
        }
    }
    
    @Override
    public void onTestFailure(ITestResult result) {
        // handle Zafira already passed exception for re-run and do nothing. Return should be enough
        if (result.getThrowable() != null && result.getThrowable().getMessage() != null
                && result.getThrowable().getMessage().startsWith(SpecialKeywords.ALREADY_PASSED)) {
            // [VD] it is prohibited to release TestInfoByThread in this place.!
            skipAlreadyPassedItem(result, Messager.TEST_SKIPPED_AS_ALREADY_PASSED);
            removeAlreadyPassedTests(result);
        } else {
            failItem(result, Messager.TEST_FAILED);
            VideoAnalyzer.enableVideoUpload();
            afterTest(result);
            super.onTestFailure(result);
        }

        // resetCounter for current thread needed to support correctly data-provider reruns (multi-threading as well)
        RetryAnalyzer retryAnalyzer = (RetryAnalyzer) result.getMethod().getRetryAnalyzer();
        if (retryAnalyzer != null) {
            retryAnalyzer.resetCounter();
        }
    }
    
    @Override
    public void onTestSkipped(ITestResult result) {
        // handle AbstractTest->SkipExecution
        if (result.getThrowable() != null && result.getThrowable().getMessage() != null
                && result.getThrowable().getMessage().startsWith(SpecialKeywords.SKIP_EXECUTION)) {
            // [VD] it is prohibited to release TestInfoByThread in this place.!
            return;
        }
        
        RetryAnalyzer retryAnalyzer = (RetryAnalyzer) result.getMethod().getRetryAnalyzer();
        int count = retryAnalyzer != null ? retryAnalyzer.getRunCount() : 0;
        int maxCount = RetryAnalyzer.getMaxRetryCountForTest();
        LOGGER.debug("count: " + count + "; maxCount:" + maxCount);
        
        IRetryAnalyzer retry = result.getMethod().getRetryAnalyzer();
        if (count > 0 && retry == null) {
            LOGGER.error("retry_count will be ignored as RetryAnalyzer is not declared for "
                    + result.getMethod().getMethodName());
        } else if (count > 0 && count <= maxCount && !Jira.isRetryDisabled(result)) {
            failRetryItem(result, Messager.RETRY_FAILED, count, maxCount + 1);
            result.setStatus(2);
            afterTest(result);
            super.onTestFailure(result);
        } else {
            skipItem(result, Messager.TEST_SKIPPED);
            afterTest(result);
            super.onTestSkipped(result);
            
            if (retryAnalyzer != null) {
                // resetCounter for current thread needed to support correctly data-provider reruns (multi-threading as well)
                retryAnalyzer.resetCounter();
            }
        }
    }

    @Override
    public void onFinish(ITestContext context) {
        super.onFinish(context);
    }

    @SuppressWarnings("unused")
    private void printContextTestsSummary(ITestContext context) {
        LOGGER.debug("getAllTestMethods length: " + context.getAllTestMethods().length);
        LOGGER.debug("---------------- PRINT SUMMARIZED SUCCESS -----------------------");
        // print messages about all tests in context
        LOGGER.debug("passed tests size: " + context.getPassedTests().getAllResults().size());
        for (Iterator<ITestResult> iterator = context.getPassedTests()
                .getAllResults().iterator(); iterator.hasNext();) {
            ITestResult testResult = iterator.next();

            long testId = getMethodId(testResult);
            LOGGER.debug("Pass test in context: " + testId + "; "
                    + testResult.getName());
        }

        LOGGER.debug("---------------- PRINT SUMMARIZED FAILURE -----------------------");
        // print messages about all tests in context
        LOGGER.debug("failed tests size: " + context.getFailedTests().getAllResults().size());
        for (Iterator<ITestResult> iterator = context.getFailedTests()
                .getAllResults().iterator(); iterator.hasNext();) {
            ITestResult testResult = iterator.next();

            long testId = getMethodId(testResult);
            LOGGER.debug("Failed test in context: " + testId + "; "
                    + testResult.getName());
        }

        LOGGER.debug("---------------- PRINT SUMMARIZED SKIP -----------------------");
        // print messages about all tests in context
        LOGGER.debug("skipped tests size: " + context.getSkippedTests().getAllResults().size());
        for (Iterator<ITestResult> iterator = context.getSkippedTests()
                .getAllResults().iterator(); iterator.hasNext();) {
            ITestResult testResult = iterator.next();

            long testId = getMethodId(testResult);
            LOGGER.debug("Skipped test in context: " + testId + "; "
                    + testResult.getName());
        }

        LOGGER.debug("---------------- PRINT SUMMARIZED CONFIGURATION SUCCESS -----------------------");
        LOGGER.debug("passed configurations size: " + context.getPassedConfigurations().getAllResults().size());
        for (Iterator<ITestResult> iterator = context.getPassedConfigurations()
                .getAllResults().iterator(); iterator.hasNext();) {
            ITestResult testResult = iterator.next();

            long testId = getMethodId(testResult);
            LOGGER.debug("passed configurations in context: " + testId + "; "
                    + testResult.getName());
        }

        LOGGER.debug("---------------- PRINT SUMMARIZED CONFIGURATION FAILURE -----------------------");
        LOGGER.debug("failed configurations size: " + context.getFailedConfigurations().getAllResults().size());
        for (Iterator<ITestResult> iterator = context.getFailedConfigurations()
                .getAllResults().iterator(); iterator.hasNext();) {
            ITestResult testResult = iterator.next();

            long testId = getMethodId(testResult);
            LOGGER.debug("failed configurations in context: " + testId + "; "
                    + testResult.getName());
        }

        LOGGER.debug("---------------- PRINT SUMMARIZED CONFIGURATION SKIP -----------------------");
        LOGGER.debug("skipped configurations size: " + context.getSkippedConfigurations().getAllResults().size());
        for (Iterator<ITestResult> iterator = context.getSkippedConfigurations()
                .getAllResults().iterator(); iterator.hasNext();) {
            ITestResult testResult = iterator.next();

            long testId = getMethodId(testResult);
            LOGGER.debug("failed configurations in context: " + testId + "; "
                    + testResult.getName());
        }
    }

    private long getMethodId(ITestResult result) {
        long id = result.getTestClass().getName().hashCode();
        id = 31 * id + result.getMethod().getMethodName().hashCode();
        id = 31
                * id
                + (result.getParameters() != null ? Arrays.hashCode(result
                        .getParameters()) : 0);
        // LOGGER.debug("Calculated id for " + result.getMethod().getMethodName() + " is " + id);
        return id;
    }

    protected TestResultItem createTestResult(ITestResult result, TestResultType resultType, String failReason,
            String description) {
        String group = TestNamingUtil.getPackageName(result);
        
        String linkToLog = ReportContext.getTestLogLink();
        String linkToScreenshots = ReportContext.getTestScreenshotsLink();

        if (TestResultType.FAIL.equals(resultType)) {
            String bugInfo = Jira.processBug(result);
            if (bugInfo != null) {
                if (failReason != null) {
                    failReason = bugInfo.concat("\n").concat(failReason);
                } else {
                    failReason = bugInfo;
                }
            }
        }

        String test = TestNamingUtil.getCanonicalTestName(result);
        TestResultItem testResultItem = new TestResultItem(group, test, resultType, linkToScreenshots, linkToLog, failReason);
        testResultItem.setDescription(description);
        // AUTO-1081 eTAF report does not show linked Jira tickets if test PASSED
        // jira tickets should be used for tracking tasks. application issues will be tracked by planned zafira feature
        testResultItem.setJiraTickets(Jira.getTickets(result));
        return testResultItem;
    }

    protected String getFailureReason(ITestResult result) {
        String errorMessage = "";
        String message = "";

        if (result.getThrowable() != null) {
            Throwable thr = result.getThrowable();
            errorMessage = getFullStackTrace(thr);
            message = thr.getMessage();
            result.getTestContext().setAttribute(SpecialKeywords.TEST_FAILURE_MESSAGE, message);
        }

        // handle in case of failed config (exclusion of expected skip)
        if (errorMessage.isEmpty()) {
            String methodName;
            Collection<ITestResult> results = result.getTestContext().getSkippedConfigurations().getAllResults();
            for (ITestResult resultItem : results) {
                methodName = resultItem.getMethod().getMethodName();
                if (methodName.equals(SpecialKeywords.BEFORE_TEST_METHOD)) {
                    errorMessage = getFullStackTrace(resultItem.getThrowable());
                }
            }
        }

        return errorMessage;
    }

    private String getFullStackTrace(Throwable thr) {
        String stackTrace = "";

        if (thr != null) {
            stackTrace = thr.getMessage() + "\n";

            StackTraceElement[] elems = thr.getStackTrace();
            for (StackTraceElement elem : elems) {
                stackTrace = stackTrace + "\n" + elem.toString();
            }
        }
        return stackTrace;
    }

    private TestResultItem getConfigFailure() {
        return configFailures.get();
    }

    protected void setConfigFailure(TestResultItem resultItem) {
        configFailures.set(resultItem);
    }
    
    private void removeFailedRetryTests(ITestResult result, int count) {
        if (count > 0) {
            ITestContext context = result.getTestContext();
            long passedTestId = getMethodId(result);
            LOGGER.debug("passedTest: " + passedTestId);

            // LOGGER.debug("---------------- REMOVED TESTS PASSED WITH RETRIES -----------------------");
            // finally delete all tests that are marked for removal
            for (Iterator<ITestResult> iterator = context.getFailedTests()
                    .getAllResults().iterator(); iterator.hasNext();) {
                ITestResult testResult = iterator.next();
                if (getMethodId(testResult) == passedTestId) {
                    LOGGER.debug("Removed test from context: " + testResult.getName());
                    iterator.remove();
                }
            }
        }
    }
    
    private void removeAlreadyPassedTests(ITestResult result) {
        //TODO: implement removal from context to make TestNG default reports cleaner
        ITestContext context = result.getTestContext();
        long alreadyPassedTestId = getMethodId(result);
        LOGGER.debug("alreadyPassedTestId: " + alreadyPassedTestId);

        // LOGGER.debug("---------------- REMOVED TESTS PASSED WITH RETRIES -----------------------");
        // finally delete all tests that are marked for removal
        for (Iterator<ITestResult> iterator = context.getFailedTests()
                .getAllResults().iterator(); iterator.hasNext();) {
            ITestResult testResult = iterator.next();
            if (getMethodId(testResult) == alreadyPassedTestId) {
                LOGGER.debug("Removed test from context: " + testResult.getName());
                iterator.remove();
            }
        }
    }

}

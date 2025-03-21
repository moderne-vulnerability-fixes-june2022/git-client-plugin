package org.jenkinsci.plugins.gitclient;

import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Git API Test which are solely for CLI git,
 * These tests are not implemented for JGit.
 */

@RunWith(Parameterized.class)
public class GitAPITestForCliGit {

    @Rule
    public GitClientSampleRepoRule repo = new GitClientSampleRepoRule();

    @Rule
    public GitClientSampleRepoRule secondRepo = new GitClientSampleRepoRule();

    private int logCount = 0;
    private final Random random = new Random();
    private static final String LOGGING_STARTED = "Logging started";
    private LogHandler handler = null;
    private TaskListener listener;
    private final String gitImplName;

    WorkspaceWithRepo workspace;

    private GitClient testGitClient;
    private File testGitDir;
    private CliGitCommand cliGitCommand;

    public GitAPITestForCliGit(final String gitImplName) {
        this.gitImplName = gitImplName;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitObjects() {
        List<Object[]> arguments = new ArrayList<>();
        String[] gitImplNames = {"git"};
        for (String gitImplName : gitImplNames) {
            Object[] item = {gitImplName};
            arguments.add(item);
        }
        return arguments;
    }

    @BeforeClass
    public static void loadLocalMirror() throws Exception {
        /* Prime the local mirror cache before other tests run */
        /* Allow 2-6 second delay before priming the cache */
        /* Allow other tests a better chance to prime the cache */
        /* 2-6 second delay is small compared to execution time of this test */
        Random random = new Random();
        Thread.sleep(2000L + random.nextInt(4000)); // Wait 2-6 seconds before priming the cache
        TaskListener mirrorListener = StreamTaskListener.fromStdout();
        File tempDir = Files.createTempDirectory("PrimeGitAPITestForCliGit").toFile();
        WorkspaceWithRepo cache = new WorkspaceWithRepo(tempDir, "git", mirrorListener);
        cache.localMirror();
        Util.deleteRecursive(tempDir);
    }

    @Before
    public void setUpRepositories() throws Exception {
        Logger logger = Logger.getLogger(this.getClass().getPackage().getName() + "-" + logCount++);
        handler = new LogHandler();
        handler.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        listener = new hudson.util.LogTaskListener(logger, Level.ALL);
        listener.getLogger().println(LOGGING_STARTED);

        workspace = new WorkspaceWithRepo(repo.getRoot(), gitImplName, listener);

        testGitClient = workspace.getGitClient();
        testGitDir = workspace.getGitFileDir();
        cliGitCommand = workspace.getCliGitCommand();
        initializeWorkspace(workspace);
    }

    private void initializeWorkspace(WorkspaceWithRepo initWorkspace) throws Exception {
        final GitClient initGitClient = initWorkspace.getGitClient();
        final CliGitCommand initCliGitCommand = initWorkspace.getCliGitCommand();
        initGitClient.init();
        final String userName = "root";
        final String emailAddress = "root@mydomain.com";
        initCliGitCommand.run("config", "user.name", userName);
        initCliGitCommand.run("config", "user.email", emailAddress);
        initGitClient.setAuthor(userName, emailAddress);
        initGitClient.setCommitter(userName, emailAddress);
    }

    @After
    public void afterTearDown() {
        try {
            String messages = StringUtils.join(handler.getMessages(), ";");
            assertTrue("Logging not started: " + messages, handler.containsMessageSubstring(LOGGING_STARTED));
        } finally {
            handler.close();
        }
    }

    @Test
    public void testPushFromShallowClone() throws Exception {
        WorkspaceWithRepo remote = new WorkspaceWithRepo(secondRepo.getRoot(), gitImplName, TaskListener.NULL);
        initializeWorkspace(remote);
        remote.commitEmpty("init");
        remote.touch(remote.getGitFileDir(), "file1", "");
        remote.getGitClient().add("file1");
        remote.getGitClient().commit("commit1");
        remote.launchCommand("git", "checkout", "-b", "other");

        workspace.launchCommand("git", "remote", "add", "origin", remote.getGitFileDir().getAbsolutePath());
        workspace.launchCommand("git", "pull", "--depth=1", "origin", "master");

        workspace.touch(testGitDir, "file2", "");
        testGitClient.add("file2");
        testGitClient.commit("commit2");
        ObjectId sha1 = workspace.head();

        try {
            testGitClient.push("origin", "master");
            assertTrue("git < 1.9.0 can push from shallow repository", workspace.cgit().isAtLeastVersion(1, 9, 0, 0));
            String remoteSha1 = remote.launchCommand("git", "rev-parse", "master").substring(0, 40);
            assertEquals(sha1.name(), remoteSha1);
        } catch (GitException ge) {
            // expected for git cli < 1.9.0
            assertExceptionMessageContains(ge, "push from shallow repository");
            assertFalse("git >= 1.9.0 can't push from shallow repository", workspace.cgit().isAtLeastVersion(1, 9, 0, 0));
        }
    }

    private void assertExceptionMessageContains(GitException ge, String expectedSubstring) {
        String actual = ge.getMessage().toLowerCase();
        assertTrue("Expected '" + expectedSubstring + "' exception message, but was: " + actual, actual.contains(expectedSubstring));
    }
}

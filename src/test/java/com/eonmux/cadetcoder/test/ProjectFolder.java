package com.eonmux.cadetcoder.test;

import org.junit.rules.TemporaryFolder;

/**
 * A temporary folder that is also the project for the length of one test.
 *
 * <p>File commands refuse paths outside the project unless {@code security.allowOutsideProject} is
 * on, and the project is the working directory, {@code user.dir}. A test that writes its fixtures
 * into a plain {@link TemporaryFolder} is therefore working outside the project, and under the
 * shipped setting every command it runs is refused. This rule points {@code user.dir} at the folder
 * before the test and restores it afterwards, so the test runs against the setting users get.</p>
 *
 * <p>Objects that read {@code user.dir} when they are constructed have to be constructed after this
 * rule has run, which means in {@code @Before} or in the test, not in a field initializer.</p>
 */
public class ProjectFolder extends TemporaryFolder {

    private String previousWorkingDir;

    @Override
    protected void before() throws Throwable {
        super.before();
        previousWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", getRoot().getAbsolutePath());
    }

    @Override
    protected void after() {
        if (previousWorkingDir != null) {
            System.setProperty("user.dir", previousWorkingDir);
        }
        super.after();
    }
}

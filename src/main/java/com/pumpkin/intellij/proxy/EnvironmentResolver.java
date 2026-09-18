package com.pumpkin.intellij.proxy;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers the project's known test-suite environments from its {@code
 * testsuite_configuration_<environment>.properties} files (e.g. {@code
 * testsuite_configuration_DEV.properties} -> environment {@code "DEV"}), for the "Base URL by
 * environment" table in {@link AddApiProxyDialog}. Each environment's own file is what {@link
 * ApiProxyCodeGenerator} appends the new proxy's {@code baseUrlProperty} line to.
 */
final class EnvironmentResolver {

    private static final Pattern CONFIG_FILE_NAME = Pattern.compile("testsuite_configuration_(.+)\\.properties");

    private EnvironmentResolver() {}

    /**
     * Every environment found project-wide, mapped to its own properties file - case-insensitively
     * ordered so the dropdown/table lists them in a predictable, readable order.
     */
    @NotNull
    static Map<String, VirtualFile> findEnvironmentFiles(@NotNull Project project) {
        Map<String, VirtualFile> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        for (VirtualFile file : FilenameIndex.getAllFilesByExt(project, "properties", scope)) {
            Matcher m = CONFIG_FILE_NAME.matcher(file.getName());
            if (m.matches()) {
                result.put(m.group(1), file);
            }
        }
        return result;
    }
}

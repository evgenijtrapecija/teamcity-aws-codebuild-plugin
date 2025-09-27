

package jetbrains.buildServer.aws.codebuild;

import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.parameters.AbstractBuildParametersProvider;
import jetbrains.buildServer.vcs.VcsRootInstanceEntry;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;

/**
 * @author vbedrosova
 */
public class GitHubVCSRootIdParameterProvider extends AbstractBuildParametersProvider {
  @NotNull
  @Override
  public Map<String, String> getParameters(@NotNull SBuild build, boolean emulationMode) {
    // Simplified implementation - just look for GitHub VCS roots
    for (VcsRootInstanceEntry e : build.getVcsRootEntries()) {
      if ("jetbrains.git".equals(e.getVcsName()) && e.getProperties().get("url").contains("github.com")) {
        // Use the VCS root ID from the entry (convert long to String)
        final String vcsRootId = String.valueOf(e.getVcsRoot().getId());
        return Collections.singletonMap(CodeBuildConstants.GIT_HUB_VCS_ROOT_ID_CONFIG_PARAM, vcsRootId);
      }
    }
    return Collections.emptyMap();
  }
}
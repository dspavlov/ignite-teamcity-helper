package org.apache.ignite.ci.web.rest.pr;

import org.apache.ignite.ci.*;
import org.apache.ignite.ci.analysis.FullChainRunCtx;
import org.apache.ignite.ci.analysis.mode.LatestRebuildMode;
import org.apache.ignite.ci.analysis.mode.ProcessLogsMode;
import org.apache.ignite.ci.conf.BranchTracked;
import org.apache.ignite.ci.tcmodel.hist.BuildRef;
import org.apache.ignite.ci.user.ICredentialsProv;
import org.apache.ignite.ci.web.BackgroundUpdater;
import org.apache.ignite.ci.web.CtxListener;
import org.apache.ignite.ci.web.rest.model.current.ChainAtServerCurrentStatus;
import org.apache.ignite.ci.web.rest.model.current.TestFailuresSummary;
import org.apache.ignite.ci.web.rest.model.current.UpdateInfo;
import org.apache.ignite.ci.web.rest.parms.FullQueryParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.apache.ignite.ci.BuildChainProcessor.loadChainsContext;

@Path(GetPrTestFailures.PR)
@Produces(MediaType.APPLICATION_JSON)
public class GetPrTestFailures {
    public static final String PR = "pr";

    @Context
    private ServletContext context;

    @Context
    private HttpServletRequest request;

    @GET
    @Path("failures/updates")
    public UpdateInfo getPrFailuresUpdates(
        @Nullable @QueryParam("serverId") String serverId,
        @Nonnull @QueryParam("suiteId") String suiteId,
        @Nonnull @QueryParam("branchForTc") String branchForTc,
        @Nonnull @QueryParam("action") String action,
        @Nullable @QueryParam("count") Integer count) {

        return new UpdateInfo().copyFrom(getPrFailures(serverId, suiteId, branchForTc, action, count));
    }

    @GET
    @Path("failures")
    public TestFailuresSummary getPrFailures(
        @Nullable @QueryParam("serverId") String serverId,
        @Nonnull @QueryParam("suiteId") String suiteId,
        @Nonnull @QueryParam("branchForTc") String branchForTc,
        @Nonnull @QueryParam("action") String action,
        @Nullable @QueryParam("count") Integer count) {

        final BackgroundUpdater updater = CtxListener.getBackgroundUpdater(context);

        final FullQueryParams key = new FullQueryParams(serverId, suiteId, branchForTc, action, count);

        return updater.get("currentPrFailures", key,
                (k) -> getPrFailuresNoCache(k.getServerId(), k.getSuiteId(), k.getBranchForTc(), k.getAction(), k.getCount()),
                true);
    }

    @GET
    @Path("failuresNoCache")
    @NotNull public TestFailuresSummary getPrFailuresNoCache(
        @Nullable @QueryParam("serverId") String srvId,
        @Nonnull @QueryParam("suiteId") String suiteId,
        @Nonnull @QueryParam("branchForTc") String branchForTc,
        @Nonnull @QueryParam("action") String action,
        @Nullable @QueryParam("count") Integer count) {

        final TestFailuresSummary res = new TestFailuresSummary();
        final AtomicInteger runningUpdates = new AtomicInteger();

        final ITcHelper tcHelper = CtxListener.getTcHelper(context);
        final ICredentialsProv creds = ICredentialsProv.get(request);

        //using here non persistent TC allows to skip update statistic
        try (IAnalyticsEnabledTeamcity teamcity = tcHelper.server(srvId, creds)) {
            LatestRebuildMode rebuild;
            if (FullQueryParams.HISTORY.equals(action))
                rebuild = LatestRebuildMode.ALL;
            else if (FullQueryParams.LATEST.equals(action))
                rebuild = LatestRebuildMode.LATEST;
            else if (FullQueryParams.CHAIN.equals(action))
                rebuild = LatestRebuildMode.NONE;
            else
                rebuild = LatestRebuildMode.LATEST;

            List<BuildRef> finishedBuilds = teamcity.getFinishedBuildsIncludeSnDepFailed(
                suiteId,
                branchForTc);

            long limit;
            if (rebuild == LatestRebuildMode.ALL)
                limit = count == null ? 10 : count;
            else
                limit = 1;

            final List<BuildRef> chains = finishedBuilds.stream()
                .filter(ref -> !ref.isFakeStub())
                .sorted(Comparator.comparing(BuildRef::getId).reversed())
                .limit(limit)
                .filter(b -> b.getId() != null).collect(Collectors.toList());

            boolean singleBuild = rebuild != LatestRebuildMode.ALL;
            ProcessLogsMode logs = singleBuild
                ? ProcessLogsMode.SUITE_NOT_COMPLETE
                : ProcessLogsMode.DISABLED;

            String failRateBranch = ITeamcity.DEFAULT;

            Optional<FullChainRunCtx> pubCtx = BuildChainProcessor.processBuildChains(teamcity, rebuild, chains,
                logs,
                singleBuild,
                true, teamcity, failRateBranch);

            final ChainAtServerCurrentStatus chainStatus = new ChainAtServerCurrentStatus(teamcity.serverId(), branchForTc);
            pubCtx.ifPresent(ctx -> {
                if (ctx.isFakeStub())
                    chainStatus.setBuildNotFound(true);
                else {
                    int cnt = (int)ctx.getRunningUpdates().count();
                    if (cnt > 0)
                        runningUpdates.addAndGet(cnt);

                    //fail rate reference is always default (master)
                    chainStatus.initFromContext(teamcity, ctx, teamcity, failRateBranch);
                }
            });

            res.addChainOnServer(chainStatus);
        }

        res.postProcess(runningUpdates.get());

        return res;
    }

}
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ci.tcbot.visa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.inject.Provider;
import org.apache.ignite.ci.ITeamcity;
import org.apache.ignite.ci.github.GitHubBranch;
import org.apache.ignite.ci.github.GitHubUser;
import org.apache.ignite.ci.github.PullRequest;
import org.apache.ignite.ci.github.ignited.IGitHubConnIgnited;
import org.apache.ignite.ci.github.ignited.IGitHubConnIgnitedProvider;
import org.apache.ignite.ci.jira.ignited.IJiraIgnited;
import org.apache.ignite.ci.jira.ignited.IJiraIgnitedProvider;
import org.apache.ignite.ci.jira.pure.Ticket;
import org.apache.ignite.ci.observer.BuildObserver;
import org.apache.ignite.ci.observer.BuildsInfo;
import org.apache.ignite.ci.tcbot.ITcBotBgAuth;
import org.apache.ignite.ci.tcbot.chain.PrChainsProcessor;
import org.apache.ignite.ci.tcbot.conf.IGitHubConfig;
import org.apache.ignite.ci.tcbot.conf.IJiraServerConfig;
import org.apache.ignite.ci.tcbot.conf.ITcBotConfig;
import org.apache.ignite.ci.tcbot.conf.ITcServerConfig;
import org.apache.ignite.ci.tcmodel.mute.MuteInfo;
import org.apache.ignite.ci.tcmodel.result.Build;
import org.apache.ignite.ci.teamcity.ignited.*;
import org.apache.ignite.ci.teamcity.ignited.buildtype.BuildTypeCompacted;
import org.apache.ignite.ci.teamcity.ignited.buildtype.BuildTypeRefCompacted;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.FatBuildCompacted;
import org.apache.ignite.ci.user.ICredentialsProv;
import org.apache.ignite.ci.web.model.*;
import org.apache.ignite.ci.web.model.current.SuiteCurrentStatus;
import org.apache.ignite.ci.web.model.current.TestFailure;
import org.apache.ignite.ci.web.model.hist.FailureSummary;
import org.apache.ignite.ci.web.model.hist.VisasHistoryStorage;
import org.apache.ignite.internal.util.typedef.F;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.ws.rs.QueryParam;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.ignite.ci.observer.BuildsInfo.*;
import static org.apache.ignite.ci.util.XmlUtil.xmlEscapeText;

/**
 * TC Bot Visa Facade. Provides method for TC Bot Visa obtaining. Contains features for adding comment to the ticket
 * based on latest state.
 */
public class TcBotTriggerAndSignOffService {
    /** Logger. */
    private static final Logger logger = LoggerFactory.getLogger(TcBotTriggerAndSignOffService.class);

    /** */
    private static final ThreadLocal<DateFormat> THREAD_FORMATTER = new ThreadLocal<DateFormat>() {
        @Override protected DateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        }
    };

    /** Build observer provider. */
    @Inject Provider<BuildObserver> buildObserverProvider;

    /** GitHub connection ignited provider. */
    @Inject IGitHubConnIgnitedProvider gitHubConnIgnitedProvider;

    /** TC ignited provider. */
    @Inject ITeamcityIgnitedProvider tcIgnitedProv;

    /** JIRA provider */
    @Inject private IJiraIgnitedProvider jiraIgnProv;

    /** */
    @Inject private VisasHistoryStorage visasHistStorage;

    /** */
    @Inject private IStringCompactor strCompactor;

    /** */
    @Inject IStringCompactor compactor;

    /** Helper. */
    @Inject ITcBotBgAuth tcBotBgAuth;

    /** PR chain processor. */
    @Inject PrChainsProcessor prChainsProcessor;

    /** Config. */
    @Inject ITcBotConfig cfg;

    @Inject BranchTicketMatcher ticketMatcher;

    /** Jackson serializer. */
    private final ObjectMapper objMapper = new ObjectMapper();

    /** */
    public void startObserver() {
        buildObserverProvider.get();
    }

    /** */
    public List<VisaStatus> getVisasStatus(String srvId, ICredentialsProv prov) {
        List<VisaStatus> visaStatuses = new ArrayList<>();

        ITeamcityIgnited ignited = tcIgnitedProv.server(srvId, prov);

        IJiraIgnited jiraIntegration = jiraIgnProv.server(srvId);

        for (VisaRequest visaRequest : visasHistStorage.getVisas()) {
            VisaStatus visaStatus = new VisaStatus();

            BuildsInfo info = visaRequest.getInfo();

            Visa visa = visaRequest.getResult();

            boolean isObserving = visaRequest.isObserving();

            visaStatus.date = THREAD_FORMATTER.get().format(info.date);
            visaStatus.branchName = info.branchForTc;
            visaStatus.userName = info.userName;
            visaStatus.ticket = info.ticket;
            visaStatus.buildTypeId = info.buildTypeId;

            BuildTypeRefCompacted bt = ignited.getBuildTypeRef(info.buildTypeId);
            visaStatus.buildTypeName = (bt != null ? bt.name(compactor) : visaStatus.buildTypeId);

            String buildsStatus = visaStatus.status = info.getStatus(ignited, strCompactor);

            if (FINISHED_STATUS.equals(buildsStatus)) {
                if (visa.isSuccess()) {
                    visaStatus.commentUrl = jiraIntegration.generateCommentUrl(
                        visaStatus.ticket, visa.getJiraCommentResponse().getId());

                    visaStatus.blockers = visa.getBlockers();

                    visaStatus.status = FINISHED_STATUS;
                }
                else
                    visaStatus.status = isObserving ? "waiting results" : CANCELLED_STATUS;
            }
            else if (RUNNING_STATUS.equals(buildsStatus))
                visaStatus.status = isObserving ? RUNNING_STATUS : CANCELLED_STATUS;
            else
                visaStatus.status = buildsStatus;

            if (isObserving)
                visaStatus.cancelUrl = "/rest/visa/cancel?server=" + srvId + "&branch=" + info.branchForTc;

            visaStatuses.add(visaStatus);
        }

        return visaStatuses;
    }

    /**
     * @param srvId Server id.
     * @param creds Credentials.
     * @return Mutes for given server-project pair.
     */
    public Set<MuteInfo> getMutes(String srvId, String projectId, ICredentialsProv creds) {
        ITeamcityIgnited ignited = tcIgnitedProv.server(srvId, creds);

        Set<MuteInfo> mutes = ignited.getMutes(projectId, creds);

        IJiraIgnited jiraIgn = jiraIgnProv.server(srvId);

        String browseUrl = jiraIgn.generateTicketUrl("");

        insertTicketStatus(mutes, jiraIgn.getTickets(), browseUrl);

        for (MuteInfo info : mutes)
            info.assignment.muteDate = THREAD_FORMATTER.get().format(new Date(info.assignment.timestamp()));

        return mutes;
    }

    /**
     * Insert ticket status for all mutes, if they have ticket in description.
     *
     * @param mutes Mutes.
     * @param tickets Tickets.
     * @param browseUrl JIRA URL for browsing tickets, e.g. https://issues.apache.org/jira/browse/
     */
    private void insertTicketStatus(Set<MuteInfo> mutes, Collection<Ticket> tickets, String browseUrl) {
        for (MuteInfo mute : mutes) {
            if (F.isEmpty(mute.assignment.text))
                continue;

            int pos = mute.assignment.text.indexOf(browseUrl);

            if (pos == -1)
                continue;

            for (Ticket ticket : tickets) {
                String muteTicket = mute.assignment.text.substring(pos + browseUrl.length());

                if (ticket.key.equals(muteTicket)) {
                    mute.ticketStatus = ticket.status();

                    break;
                }
            }
        }
    }

    @NotNull public String triggerBuildsAndObserve(
        @Nullable String srvId,
        @Nullable String branchForTc,
        @Nonnull String parentSuiteId,
        @Nonnull String suiteIdList,
        @Nullable Boolean top,
        @Nullable Boolean observe,
        @Nullable String ticketId,
        @Nullable String prNum,
        @Nullable ICredentialsProv prov) {
        String jiraRes = "";

        ITeamcityIgnited teamcity = tcIgnitedProv.server(srvId, prov);

        IGitHubConnIgnited ghIgn = gitHubConnIgnitedProvider.server(srvId);

        if(!Strings.isNullOrEmpty(prNum)) {
            try {
                PullRequest pr = ghIgn.getPullRequest(Integer.parseInt(prNum));

                if(pr!=null) {
                    String shaShort = pr.lastCommitShaShort();

                    if(shaShort!=null)
                         jiraRes = "Actual commit: " + shaShort + ". ";
                }
            }
            catch (NumberFormatException e) {
                logger.error("PR & TC state checking failed" , e);
            }
        }

        String[] suiteIds = Objects.requireNonNull(suiteIdList).split(",");

        //todo consult if there are change differences here https://ci.ignite.apache.org/app/rest/changes?locator=buildType:(id:IgniteTests24Java8_Cache7),pending:true,branch:pull%2F6224%2Fhead
        Build[] builds = new Build[suiteIds.length];

        for (int i = 0; i < suiteIds.length; i++)
            builds[i] = teamcity.triggerBuild(suiteIds[i], branchForTc, false, top != null && top);

        if (observe != null && observe)
            jiraRes += observeJira(srvId, branchForTc, ticketId, prov, parentSuiteId, builds);

        return jiraRes;
    }

    /**
     * @param srvId Server id.
     * @param branchForTc Branch for TeamCity.
     * @param ticketFullName JIRA ticket number.
     * @param prov Credentials.
     * @param builds Builds.
     * @return Message with result.
     */
    private String observeJira(
        String srvId,
        String branchForTc,
        @Nullable String ticketFullName,
        ICredentialsProv prov,
        String parentSuiteId,
        Build... builds
    ) {
        try {
            ticketFullName = ticketMatcher.resolveTicketFromBranch(srvId, ticketFullName, branchForTc);
        }
        catch (BranchTicketMatcher.TicketNotFoundException e) {
            logger.info("", e);
            return "JIRA ticket will not be notified after the tests are completed - " +
                "exception happened when server tried to get ticket ID from Pull Request [errMsg="
                + e.getMessage();
        }

        buildObserverProvider.get().observe(srvId, prov, ticketFullName, branchForTc, parentSuiteId, builds);

        if (!tcBotBgAuth.isServerAuthorized())
            return "Ask server administrator to authorize the Bot to enable JIRA notifications.";

        return "JIRA ticket " + ticketFullName + " will be notified after the tests are completed.";
    }

    /**
     * @param srvId Server id.
     * @param branchForTc Branch for tc.
     * @param suiteId Suite id.
     * @param ticketFullName Ticket full name with IGNITE- prefix.
     * @param prov Prov.
     */
    @NotNull
    public SimpleResult commentJiraEx(
        @QueryParam("serverId") @Nullable String srvId,
        @QueryParam("branchName") @Nullable String branchForTc,
        @QueryParam("suiteId") @Nullable String suiteId,
        @QueryParam("ticketId") @Nullable String ticketFullName,
        ICredentialsProv prov) {

        try {
            ticketFullName = ticketMatcher.resolveTicketFromBranch(srvId, ticketFullName, branchForTc);
        }
        catch (BranchTicketMatcher.TicketNotFoundException e) {
            logger.info("", e);
            return new SimpleResult("JIRA wasn't commented.<br>" + e.getMessage());
        }

        BuildsInfo buildsInfo = new BuildsInfo(srvId, prov, ticketFullName, branchForTc, suiteId);

        VisaRequest lastVisaReq = visasHistStorage.getLastVisaRequest(buildsInfo.getContributionKey());

        if (Objects.nonNull(lastVisaReq) && lastVisaReq.isObserving())
            return new SimpleResult("Jira wasn't commented." +
                " \"Re-run possible blockers & Comment JIRA\" was triggered for current branch." +
                " Wait for the end or cancel exsiting observing.");

        Visa visa = notifyJira(srvId, prov, suiteId, branchForTc, ticketFullName);

        visasHistStorage.put(new VisaRequest(buildsInfo).setResult(visa));

        return new SimpleResult(visa.status);
    }

    /**
     * @param srvCodeOrAlias Server id.
     * @param credsProv Credentials
     */
    public List<ContributionToCheck> getContributionsToCheck(String srvCodeOrAlias,
        ICredentialsProv credsProv) {
        IJiraIgnited jiraIntegration = jiraIgnProv.server(srvCodeOrAlias);

        IGitHubConnIgnited gitHubConnIgnited = gitHubConnIgnitedProvider.server(srvCodeOrAlias);

        ITeamcityIgnited tcIgn = tcIgnitedProv.server(srvCodeOrAlias, credsProv);

        List<PullRequest> prs = gitHubConnIgnited.getPullRequests();

        Set<Ticket> tickets = jiraIntegration.getTickets();

        IJiraServerConfig jiraCfg = jiraIntegration.config();
        IGitHubConfig ghCfg = gitHubConnIgnited.config();

        String defBtForTcServ = findDefaultBuildType(srvCodeOrAlias);

        List<ContributionToCheck> contribsList = new ArrayList<>();

        if (prs != null) {
            prs.forEach(pr -> {
                ContributionToCheck c = new ContributionToCheck();

                c.prNumber = pr.getNumber();
                c.prTitle = pr.getTitle();
                c.prHtmlUrl = pr.htmlUrl();
                c.prHeadCommit = pr.lastCommitShaShort();
                c.prTimeUpdate = pr.getTimeUpdate();

                GitHubUser user = pr.gitHubUser();
                if (user != null) {
                    c.prAuthor = user.login();
                    c.prAuthorAvatarUrl = user.avatarUrl();
                } else {
                    c.prAuthor = "";
                    c.prAuthorAvatarUrl = "";
                }

                Ticket ticket = ticketMatcher.resolveTicketIdForPrBasedContrib(tickets, pr, jiraCfg);

                c.jiraIssueId = ticket == null ? null : ticket.key;
                c.jiraStatusName = ticket == null ? null : ticket.status();

                if (!Strings.isNullOrEmpty(c.jiraIssueId)
                        && jiraCfg.getUrl() != null)
                    c.jiraIssueUrl = jiraIntegration.generateTicketUrl(c.jiraIssueId);

                findBuildsForPr(defBtForTcServ, Integer.toString(pr.getNumber()), gitHubConnIgnited, tcIgn)
                        .stream()
                        .map(buildRefCompacted -> buildRefCompacted.branchName(compactor))
                        .findAny()
                        .ifPresent(bName -> c.tcBranchName = bName);

                contribsList.add(c);
            });
        }

        List<String> branches = gitHubConnIgnited.getBranches();

        List<Ticket> activeTickets = tickets.stream().filter(Ticket::isActiveContribution).collect(Collectors.toList());

        activeTickets.forEach(ticket -> {
            String branch = ticketMatcher.resolveTcBranchForPrLess(ticket,
                jiraCfg,
                ghCfg);

            if (Strings.isNullOrEmpty(branch))
                return; // nothing to do if branch was not resolved


            if (!branches.contains(branch)
                && tcIgn.getAllBuildsCompacted(defBtForTcServ, branch).isEmpty())
                return; //Skipping contributions without builds

            ContributionToCheck contribution = new ContributionToCheck();

            contribution.jiraIssueId = ticket.key;
            contribution.jiraStatusName = ticket.status();
            contribution.jiraIssueUrl = jiraIntegration.generateTicketUrl(ticket.key);
            contribution.tcBranchName = branch;

            if (branch.startsWith(ghCfg.gitBranchPrefix())) {
                String branchTc = branch.substring(ghCfg.gitBranchPrefix().length());

                try {
                    contribution.prNumber = -Integer.valueOf(branchTc);
                }
                catch (NumberFormatException e) {
                    logger.error("PR less contribution has invalid branch name", e);
                }
            }

            contribution.prTitle = ticket.fields.summary;
            contribution.prHtmlUrl = "";
            contribution.prHeadCommit = "";
            contribution.prTimeUpdate = ""; //todo ticket updateTime

            contribution.prAuthor = "";
            contribution.prAuthorAvatarUrl = "";

            contribsList.add(contribution);
        });

        return contribsList;
    }

    /**
     * @param suiteId Suite id.
     * @param prId Pr id from {@link ContributionToCheck#prNumber}. Negative value imples branch number for PR-less.
     * @param ghConn Gh connection.
     * @param srv TC Server connection.
     */
    @Nonnull
    private List<BuildRefCompacted> findBuildsForPr(String suiteId,
        String prId,
        IGitHubConnIgnited ghConn,
        ITeamcityIgnited srv) {

        List<BuildRefCompacted> buildHist = srv.getAllBuildsCompacted(suiteId,
                branchForTcDefault(prId, ghConn));

        if (!buildHist.isEmpty())
            return buildHist;

        Integer prNum = Integer.valueOf(prId);
        if (prNum < 0)
            return buildHist; // Don't iterate for other options if PR ID is absent

        buildHist = srv.getAllBuildsCompacted(suiteId, branchForTcB(prId));

        if (!buildHist.isEmpty())
            return buildHist;

        String bracnhToCheck =
                ghConn.config().isPreferBranches()
                        ? branchForTcA(prId) // for prefer branches mode it was already checked in default
                        : getPrBranch(ghConn, prNum);

        if (bracnhToCheck == null)
            return Collections.emptyList();

        buildHist = srv.getAllBuildsCompacted(suiteId, bracnhToCheck);

        return buildHist;
    }

    @Nullable
    private String getPrBranch(IGitHubConnIgnited ghConn, Integer prNum) {
        PullRequest pr = ghConn.getPullRequest(prNum);

        if (pr == null)
            return null;

        GitHubBranch head = pr.head();

        if (head == null)
            return null;

        return head.ref();
    }

    /**
     * @param prId Pr id from {@link ContributionToCheck#prNumber}. Negative value imples branch number to be used for
     * PR-less contributions.
     * @param ghConn Github integration.
     */
    private String branchForTcDefault(String prId, IGitHubConnIgnited ghConn) {
        Integer prNum = Integer.valueOf(prId);
        if (prNum < 0)
            return ghConn.gitBranchPrefix() + (-prNum); // Checking "ignite-10930" builds only

        if (ghConn.config().isPreferBranches()) {
            String ref = getPrBranch(ghConn, prNum);
            if (ref != null)
                return ref;
        }

        return branchForTcA(prId);
    }

    private String branchForTcA(String prId) {
        return "pull/" + prId + "/head";
    }

    private String branchForTcB(String prId) {
        return "pull/" + prId + "/merge";
    }

    /**
     * @param srvCode Server (service) internal code.
     * @param prov Prov.
     * @param prId Pr id from {@link ContributionToCheck#prNumber}. Negative value imples branch number (with
     * appropriate prefix from GH config).
     */
    public Set<ContributionCheckStatus> contributionStatuses(String srvCode, ICredentialsProv prov,
        String prId) {
        Set<ContributionCheckStatus> statuses = new LinkedHashSet<>();

        ITeamcityIgnited teamcity = tcIgnitedProv.server(srvCode, prov);

        IGitHubConnIgnited ghConn = gitHubConnIgnitedProvider.server(srvCode);

        Preconditions.checkState(ghConn.config().code().equals(srvCode));

        List<String> compositeBuildTypeIds = findApplicableBuildTypes(srvCode, teamcity);

        for (String btId : compositeBuildTypeIds) {
            List<BuildRefCompacted> buildsForBt = findBuildsForPr(btId, prId, ghConn, teamcity);

            statuses.add(buildsForBt.isEmpty()
                ? new ContributionCheckStatus(btId, branchForTcDefault(prId, ghConn))
                : contributionStatus(srvCode, btId, buildsForBt, teamcity, ghConn, prId));
        }

        return statuses;
    }

    /**
     *
     * @param srvIdOrAlias TC server ID or reference to it.
     * @param teamcity Teamcity.
     * @return list of build types which may be taken for
     */
    public List<String> findApplicableBuildTypes(String srvIdOrAlias, ITeamcityIgnited teamcity) {
        String defBtForMaster = findDefaultBuildType(srvIdOrAlias);

        BuildTypeCompacted buildType = Strings.isNullOrEmpty(defBtForMaster)
            ? null
            : teamcity.getBuildType(defBtForMaster);

        List<String> compositeBuildTypeIds;
        String projectId;
        if (buildType != null) {
            projectId = compactor.getStringFromId(buildType.projectId());
            compositeBuildTypeIds = teamcity.getCompositeBuildTypesIdsSortedByBuildNumberCounter(projectId);
        }
        else {
            //for case build type not found, actualizing all projects resync
            List<String> projects = teamcity.getAllProjectsIds();

            for (String pId : projects)
                teamcity.getCompositeBuildTypesIdsSortedByBuildNumberCounter(pId);

            compositeBuildTypeIds = new ArrayList<>();

            if (!Strings.isNullOrEmpty(defBtForMaster))
                compositeBuildTypeIds.add(defBtForMaster);
        }
        return compositeBuildTypeIds;
    }

    /**
     * @param srvIdOrAlias Server id. May be weak reference to TC
     * @return Some build type included into tracked branches with default branch.
     */
    @NotNull
    private String findDefaultBuildType(String srvIdOrAlias) {
        StringBuilder buildTypeId = new StringBuilder();

        ITcServerConfig tcCfg = cfg.getTeamcityConfig(srvIdOrAlias);
        String trBranch = tcCfg.defaultTrackedBranch();

        String realTcId = Strings.isNullOrEmpty(tcCfg.reference()) ? srvIdOrAlias : tcCfg.reference();

        cfg.getTrackedBranches()
            .get(trBranch)
            .ifPresent(
                b -> b.getChainsStream()
                    .filter(c -> Objects.equals(realTcId, c.serverId))
                    .filter(c -> c.branchForRest.equals(ITeamcity.DEFAULT))
                    .findFirst()
                    .ifPresent(ch -> buildTypeId.append(ch.suiteId)));

        return buildTypeId.toString();
    }

    /**
     * @param srvId Server id.
     * @param suiteId Suite id.
     * @param builds Build references.
     * @param ghConn GitHub integration.
     */
    public ContributionCheckStatus contributionStatus(String srvId, String suiteId, List<BuildRefCompacted> builds,
        ITeamcityIgnited teamcity, IGitHubConnIgnited ghConn, String prId) {
        ContributionCheckStatus status = new ContributionCheckStatus();

        status.suiteId = suiteId;

        List<BuildRefCompacted> finishedOrCancelled = builds.stream()
            .filter(t -> t.isFinished(compactor)).collect(Collectors.toList());

        if (!finishedOrCancelled.isEmpty()) {
            BuildRefCompacted buildRefCompacted = finishedOrCancelled.get(0);

            status.suiteIsFinished = !buildRefCompacted.isCancelled(compactor);
            status.branchWithFinishedSuite = buildRefCompacted.branchName(compactor);

            FatBuildCompacted fatBuild = teamcity.getFatBuild(buildRefCompacted.id(), SyncMode.NONE);

            String commit = teamcity.getLatestCommitVersion(fatBuild);

            if (!Strings.isNullOrEmpty(commit) && commit.length() > PullRequest.INCLUDE_SHORT_VER) {
                status.finishedSuiteCommit
                    = commit.substring(0, PullRequest.INCLUDE_SHORT_VER).toLowerCase();
            }
        }
        else {
            status.branchWithFinishedSuite = null;
            status.finishedSuiteCommit = null;
            status.suiteIsFinished = false;
        }

        if (status.branchWithFinishedSuite != null)
            status.resolvedBranch = status.branchWithFinishedSuite;
            //todo take into account running/queued
        else
            status.resolvedBranch = !builds.isEmpty() ? builds.get(0).branchName(compactor) : branchForTcDefault(prId, ghConn);

        String observationsStatus = buildObserverProvider.get().getObservationStatus(new ContributionKey(srvId, status.resolvedBranch));

        status.observationsStatus = Strings.emptyToNull(observationsStatus);

        List<BuildRefCompacted> queuedSuites = builds.stream()
            .filter(t -> t.isNotCancelled(compactor))
            .filter(t -> t.isQueued(compactor))
            .collect(Collectors.toList());

        List<BuildRefCompacted> runningSuites = builds.stream()
            .filter(t -> t.isNotCancelled(compactor))
            .filter(t -> t.isRunning(compactor))
            .collect(Collectors.toList());

        status.queuedBuilds = queuedSuites.size();
        status.runningBuilds = runningSuites.size();

        status.webLinksQueuedSuites = Stream.concat(queuedSuites.stream(), runningSuites.stream())
            .map(ref -> getWebLinkToQueued(teamcity, ref)).collect(Collectors.toList());

        return status;
    }

    //later may move it to BuildRef webUrl
    /**
     * @param teamcity Teamcity.
     * @param ref Reference.
     */
    @NotNull public String getWebLinkToQueued(ITeamcityIgnited teamcity, BuildRefCompacted ref) {
        return teamcity.host() + "viewQueued.html?itemId=" + ref.id();
    }

    public CurrentVisaStatus currentVisaStatus(String srvCode, ICredentialsProv prov, String buildTypeId,
        String tcBranch) {
        CurrentVisaStatus status = new CurrentVisaStatus();

        List<SuiteCurrentStatus> suitesStatuses
            = prChainsProcessor.getBlockersSuitesStatuses(buildTypeId, tcBranch, srvCode, prov, SyncMode.NONE);

        if (suitesStatuses == null)
            return status;

        status.blockers = suitesStatuses.stream()
            .mapToInt(suite -> {
                if (suite.testFailures.isEmpty())
                    return 1;

                return suite.testFailures.size();
            })
            .sum();

        return status;
    }

    /**
     * Produce visa message(see {@link Visa}) based on passed parameters and publish it as a comment for specified
     * ticket on Jira server.
     *
     * @param srvId TC Server ID to take information about token from.
     * @param prov Credentials.
     * @param buildTypeId Build type ID, for which visa was ordered.
     * @param branchForTc Branch for TeamCity.
     * @param ticket JIRA ticket full name. E.g. IGNITE-5555
     * @return {@link Visa} instance.
     */
    public Visa notifyJira(
        String srvId,
        ICredentialsProv prov,
        String buildTypeId,
        String branchForTc,
        String ticket
    ) {
        ITeamcityIgnited tcIgnited = tcIgnitedProv.server(srvId, prov);

        IJiraIgnited jira = jiraIgnProv.server(srvId);

        List<Integer> builds = tcIgnited.getLastNBuildsFromHistory(buildTypeId, branchForTc, 1);

        if (builds.isEmpty())
            return new Visa("JIRA wasn't commented - no finished builds to analyze.");

        Integer buildId = builds.get(0);

        FatBuildCompacted fatBuild = tcIgnited.getFatBuild(buildId);
        Build build = fatBuild.toBuild(compactor);

        build.webUrl = tcIgnited.host() + "viewLog.html?buildId=" + build.getId() + "&buildTypeId=" + build.buildTypeId;

        int blockers;

        JiraCommentResponse res;

        try {
            List<SuiteCurrentStatus> suitesStatuses = prChainsProcessor.getBlockersSuitesStatuses(buildTypeId, build.branchName, srvId, prov);

            if (suitesStatuses == null)
                return new Visa("JIRA wasn't commented - no finished builds to analyze.");

            String comment = generateJiraComment(suitesStatuses, build.webUrl, buildTypeId, tcIgnited);

            blockers = suitesStatuses.stream()
                .mapToInt(suite -> {
                    if (suite.testFailures.isEmpty())
                        return 1;

                    return suite.testFailures.size();
                })
                .sum();

            res = objMapper.readValue(jira.postJiraComment(ticket, comment), JiraCommentResponse.class);
        }
        catch (Exception e) {
            String errMsg = "Exception happened during commenting JIRA ticket " +
                "[build=" + build.getId() + ", errMsg=" + e.getMessage() + ']';

            logger.error(errMsg);

            return new Visa("JIRA wasn't commented - " + errMsg);
        }

        return new Visa(Visa.JIRA_COMMENTED, res, blockers);
    }

    /**
     * @param suites Suite Current Status.
     * @param webUrl Build URL.
     * @param buildTypeId Build type ID, for which visa was ordered.
     * @param tcIgnited TC service.
     * @return Comment, which should be sent to the JIRA ticket.
     */
    private String generateJiraComment(List<SuiteCurrentStatus> suites, String webUrl, String buildTypeId,
        ITeamcityIgnited tcIgnited) {
        BuildTypeRefCompacted bt = tcIgnited.getBuildTypeRef(buildTypeId);

        String suiteNameUsedForVisa = (bt != null ? bt.name(compactor) : buildTypeId);

        StringBuilder res = new StringBuilder();

        for (SuiteCurrentStatus suite : suites) {
            res.append("{color:#d04437}").append(jiraEscText(suite.name)).append("{color}");
            res.append(" [[tests ").append(suite.failedTests);

            if (suite.result != null && !suite.result.isEmpty())
                res.append(' ').append(suite.result);

            res.append('|').append(suite.webToBuild).append("]]\\n");

            for (TestFailure failure : suite.testFailures) {
                res.append("* ");

                if (failure.suiteName != null && failure.testName != null)
                    res.append(jiraEscText(failure.suiteName)).append(": ").append(jiraEscText(failure.testName));
                else
                    res.append(jiraEscText(failure.name));

                FailureSummary recent = failure.histBaseBranch.recent;

                if (recent != null) {
                    if (recent.failureRate != null) {
                        res.append(" - ").append(recent.failureRate).append("% fails in last ")
                            .append(recent.runs).append(" master runs.");
                    }
                    else if (recent.failures != null && recent.runs != null) {
                        res.append(" - ").append(recent.failures).append(" fails / ")
                            .append(recent.runs).append(" master runs.");
                    }
                }

                res.append("\\n");
            }

            res.append("\\n");
        }

        if (res.length() > 0) {
            res.insert(0, "{panel:title=" + jiraEscText(suiteNameUsedForVisa) + ": Possible Blockers|" +
                "borderStyle=dashed|borderColor=#ccc|titleBGColor=#F7D6C1}\\n")
                .append("{panel}");
        }
        else {
            res.append("{panel:title=").append(jiraEscText(suiteNameUsedForVisa)).append(": No blockers found!|")
                .append("borderStyle=dashed|borderColor=#ccc|titleBGColor=#D6F7C1}{panel}");
        }

        res.append("\\n").append("[TeamCity *").append(jiraEscText(suiteNameUsedForVisa)).append("* Results|").append(webUrl).append(']');

        return xmlEscapeText(res.toString());
    }

    /**
     * Escapes text for JIRA.
     * @param txt Txt.
     */
    private String jiraEscText(String txt) {
        if(Strings.isNullOrEmpty(txt))
            return "";

        return txt.replaceAll("\\|", "/");
    }
}

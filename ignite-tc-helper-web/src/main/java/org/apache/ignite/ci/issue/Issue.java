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

package org.apache.ignite.ci.issue;

import com.google.common.base.MoreObjects;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.ignite.ci.db.Persisted;
import org.apache.ignite.ci.util.TimeUtil;
import org.jetbrains.annotations.Nullable;

/**
 *
 */
@SuppressWarnings("WeakerAccess")
@Persisted
public class Issue {
    /** Type. Null of older versions of issue */
    @Nullable
    private String type;

    /** Display type. for issue. Kept for backward compatibilty with older records without type code. */
    private String displayType;

    @Nullable
    public String trackedBranchName;

    public IssueKey issueKey;

    public List<ChangeUi> changes = new ArrayList<>();

    public Set<String> addressNotified = new TreeSet<>();

    @Nullable
    public String webUrl;

    @Nullable
    public String displayName;

    @Nullable public Long buildStartTs;
    @Nullable public Long detectedTs;

    public Issue(IssueKey issueKey, IssueType type) {
        this.issueKey = issueKey;
        this.detectedTs = System.currentTimeMillis();
        this.type = type.code();
        this.displayType = type.displayName();
    }

    public void addChange(String username, String webUrl) {
        changes.add(new ChangeUi(username, webUrl));
    }

    public String toHtml(boolean includeChangesInfo) {
        StringBuilder sb = new StringBuilder();

        sb.append(displayType);

        if (trackedBranchName != null)
            sb.append(" in ").append(trackedBranchName);

        sb.append(" ");

        if (webUrl != null)
            sb.append("<a href='").append(webUrl).append("'>").append(getDisplayName()).append("</a>");
        else
            sb.append(getDisplayName());


        if(includeChangesInfo) {
            if (changes.isEmpty())
                sb.append(" No changes in the build");
            else {

                sb.append(" Changes may lead to failure were done by ");

                for (Iterator<ChangeUi> iter = changes.iterator(); iter.hasNext(); ) {
                    ChangeUi next = iter.next();
                    sb.append(next.toHtml());

                    if (iter.hasNext())
                        sb.append(", ");
                }
            }
        }

        return sb.toString();
    }

    public IssueKey issueKey() {
        return issueKey;
    }

    public String toSlackMarkup(boolean includeChangesInfo) {
        StringBuilder sb = new StringBuilder();

        sb.append(displayType);

        if (trackedBranchName != null)
            sb.append(" in ").append(trackedBranchName);

        sb.append(" ");

        String name = getDisplayName();

        if (webUrl != null)
            sb.append("<").append(webUrl).append("|").append(name).append(">");
        else
            sb.append(name);

        if(includeChangesInfo) {
            if (changes.isEmpty())
                sb.append("\n No changes in the build");
            else {

                sb.append("\n Changes may led to failure were done by ");

                for (Iterator<ChangeUi> iter = changes.iterator(); iter.hasNext(); ) {
                    ChangeUi next = iter.next();
                    sb.append(next.toSlackMarkup());

                    if (iter.hasNext())
                        sb.append(", ");
                }
            }
        }

        return sb.toString();
    }

    public String getDisplayName() {
        if(displayName==null)
            return issueKey.getTestOrBuildName();

        return displayName;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        String tsStart = buildStartTs == null ? null : TimeUtil.timestampToDateTimePrintable(buildStartTs);
        String tsDetect = detectedTs == null ? null : TimeUtil.timestampToDateTimePrintable(detectedTs);
        return MoreObjects.toStringHelper(this)
            .add("type", type)
            .add("displayType", displayType)
            .add("trackedBranchName", trackedBranchName)
            .add("issueKey", issueKey)
            .add("changes", changes)
            .add("addressNotified", addressNotified)
            .add("webUrl", webUrl)
            .add("displayName", displayName)
            .add("buildStartTs", tsStart)
            .add("detectedTs", tsDetect)
            .toString();
    }

    public String toPlainText(boolean includeChangesInfo) {
        StringBuilder sb = new StringBuilder();

        sb.append(displayType);

        if (trackedBranchName != null)
            sb.append(" in ").append(trackedBranchName);

        sb.append(" ");

        if (webUrl != null)
            sb.append("").append(getDisplayName()).append(" ").append(webUrl).append("");
        else
            sb.append(getDisplayName());

        sb.append("\n");

        if(includeChangesInfo) {
            if (changes.isEmpty())
                sb.append(" No changes in the build");
            else {

                sb.append(" Changes may lead to failure were done by \n");

                for (Iterator<ChangeUi> iter = changes.iterator(); iter.hasNext(); ) {
                    ChangeUi next = iter.next();

                    sb.append("\t - ");
                    sb.append(next.toPlainText());

                    if (iter.hasNext())
                        sb.append("\n");
                }
            }
        }

        return sb.toString();
    }
}

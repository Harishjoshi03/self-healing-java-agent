package com.example.healing.github;

import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.patch.IsolatedWorkspace;
import java.util.List;

public interface PullRequestService {
    PullRequestResult create(IncidentEntity incident, IsolatedWorkspace workspace, String summary,
                             List<String> changedFiles, String testReport);
}
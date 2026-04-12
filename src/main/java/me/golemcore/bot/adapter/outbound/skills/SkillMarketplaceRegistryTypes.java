package me.golemcore.bot.adapter.outbound.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

record MarketplaceSource(String type,String displayValue,String repositoryUrl,String branch,Path localRepositoryRoot){}

record RegistryArtifact(String artifactRef,String maintainerId,String maintainerDisplayName,String artifactId,String type,String version,String title,String description,String manifestPath,String contentHash,List<RegistrySkill>skills){}

record RegistrySkill(String skillId,String sourcePath,String runtimeName,String originalName,String description,String modelTier){}

record ArtifactSkillEntry(String id,String path){}

record SkillMetadata(String name,String description,String modelTier){}

record MaintainerInfo(String id,String displayName){}

record RemoteRepositoryTree(List<String>filePaths){}

record RemoteCatalogCache(String sourceKey,Instant loadedAt,Map<String,RegistryArtifact>artifacts){}

record GitHubRepository(String owner,String name){}

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
final class ArtifactManifest {
    private String schema;
    private String type;
    private String maintainer;
    private String id;
    private String version;
    private String title;
    private String description;
    private String license;
    private ArtifactSourceManifest source;
    private String attribution;
    private List<String> tags = List.of();
    private List<ArtifactSkillManifest> skills = List.of();
}

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
final class ArtifactSourceManifest {
    private String repository;
    private String author;
    @JsonProperty("author_url")
    private String authorUrl;
    private String license;
}

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
final class ArtifactSkillManifest {
    private String id;
    private String path;
}

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
final class MaintainerManifest {
    private String schema;
    private String id;
    @JsonProperty("display_name")
    private String displayName;
    private String github;
    private String contact;
}

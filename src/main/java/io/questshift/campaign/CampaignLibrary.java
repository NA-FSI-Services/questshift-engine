package io.questshift.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CampaignLibrary {

    private static final Logger LOG = Logger.getLogger(CampaignLibrary.class);
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final Map<String, Campaign> campaigns = new ConcurrentHashMap<>();

    @ConfigProperty(name = "questshift.campaigns.dir")
    String campaignsDir;

    @ConfigProperty(name = "questshift.campaigns.default-id", defaultValue = "devops-dungeon")
    String defaultId;

    public Campaign defaultCampaign() {
        return require(defaultId);
    }

    public Campaign require(String id) {
        loadIfEmpty();
        Campaign campaign = campaigns.get(id);
        if (campaign == null) {
            throw new IllegalArgumentException("Unknown campaign: " + id);
        }
        return campaign;
    }

    public Collection<Campaign> all() {
        loadIfEmpty();
        return campaigns.values();
    }

    private synchronized void loadIfEmpty() {
        if (!campaigns.isEmpty()) {
            return;
        }
        Path dir = Path.of(campaignsDir);
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                        .forEach(this::loadFile);
            } catch (IOException e) {
                LOG.warn("Could not list campaign dir " + dir, e);
            }
        }
        if (campaigns.isEmpty()) {
            loadClasspath("campaigns/campaign-devops-dungeon.yaml");
        }
        LOG.infof("Loaded %d campaign(s)", campaigns.size());
    }

    private void loadFile(Path path) {
        try {
            Campaign campaign = yaml.readValue(path.toFile(), Campaign.class);
            campaigns.put(campaign.metadata.id, campaign);
        } catch (IOException e) {
            LOG.error("Failed to read " + path, e);
        }
    }

    private void loadClasspath(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOG.error("Missing classpath campaign " + resource);
                return;
            }
            Campaign campaign = yaml.readValue(in, Campaign.class);
            campaigns.put(campaign.metadata.id, campaign);
        } catch (IOException e) {
            LOG.error("Failed to read classpath campaign", e);
        }
    }
}

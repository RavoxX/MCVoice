package dev.mcvoice.client.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.mcvoice.client.ui.VoiceControls.GroupInfo;

/** Serializes page requests and rejects responses belonging to an older search/session. */
final class GroupBrowser {
    static final int PAGE_SIZE = 20;
    interface Sender {
        boolean send(String query, String cursor, long requestId, boolean paged);
    }

    private List<GroupInfo> entries = Collections.emptyList();
    private List<GroupInfo> legacy = Collections.emptyList();
    private String query = "", cursor;
    private long generation, sentGeneration, sequence, requestId, sentAt, nextRequestAt;
    private boolean wanted, pending, pagedRequest;

    synchronized List<GroupInfo> entries() { return entries; }
    synchronized boolean loading() { return wanted || pending; }
    synchronized boolean hasMore() { return cursor != null || entries.size() < legacy.size(); }

    synchronized void search(String value) {
        query = value == null ? "" : value.toUpperCase(Locale.ROOT);
        generation++;
        entries = Collections.emptyList();
        legacy = Collections.emptyList();
        cursor = null;
        wanted = true;
    }

    synchronized void more() {
        if (wanted || pending) return;
        if (entries.size() < legacy.size()) {
            entries = Collections.unmodifiableList(new ArrayList<GroupInfo>(legacy.subList(0,
                Math.min(legacy.size(), entries.size() + PAGE_SIZE))));
        } else if (cursor != null) {
            wanted = true;
        }
    }

    synchronized void clear() {
        generation++;
        entries = Collections.emptyList();
        legacy = Collections.emptyList();
        cursor = null;
        wanted = pending = false;
    }

    synchronized void tick(Sender sender, boolean paged, long now) {
        if (pending && now - sentAt >= 5000) pending = false;
        if (!wanted || pending || now < nextRequestAt) return;
        sequence = sequence >= 0x7FFFFFFFL ? 1 : sequence + 1;
        requestId = sequence;
        sentGeneration = generation;
        sentAt = now;
        nextRequestAt = now + 500;
        pagedRequest = paged;
        pending = true;
        wanted = false;
        if (!sender.send(query, cursor, requestId, paged)) pending = false;
    }

    synchronized void receive(long id, String nextCursor, List<GroupInfo> page) {
        if (!pending || (pagedRequest && id != requestId)) return;
        pending = false;
        if (generation != sentGeneration) return;
        if (!pagedRequest) {
            legacy = new ArrayList<GroupInfo>(page);
            entries = Collections.unmodifiableList(new ArrayList<GroupInfo>(page.subList(0, Math.min(PAGE_SIZE, page.size()))));
            cursor = null;
            return;
        }
        Map<String, GroupInfo> merged = new LinkedHashMap<String, GroupInfo>();
        for (GroupInfo g : entries) merged.put(g.id, g);
        for (int i = 0; i < Math.min(PAGE_SIZE, page.size()); i++) merged.put(page.get(i).id, page.get(i));
        entries = Collections.unmodifiableList(new ArrayList<GroupInfo>(merged.values()));
        cursor = nextCursor != null && !nextCursor.isEmpty() && !nextCursor.equals(cursor) ? nextCursor : null;
    }

    synchronized void rateLimited(long now) {
        wanted |= pending;
        pending = false;
        nextRequestAt = Math.max(nextRequestAt, now + 1000);
    }
}

package org.wyrdsekai.core.app.photo;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Photo Fabric service (§14).
 * Manages photo metadata, face groups, tags, and deduplication.
 * The actual photo files live on disk; this tracks metadata only.
 *
 * M0 scope: metadata management and tagging.
 * M2+: ML-based face recognition, auto-tagging, dedup via perceptual hashing.
 */
public class PhotoService {

    /** Photo metadata record. */
    public record Photo(
        String photoId,
        String filename,
        String ownerEntity,
        Instant takenAt,
        Instant importedAt,
        String location,       // GPS or descriptive
        Set<String> tags,
        Set<String> faces,     // recognized face IDs
        String perceptualHash, // for dedup
        PhotoStatus status
    ) {}

    public enum PhotoStatus { IMPORTED, TAGGED, ARCHIVED, DELETED }

    /** A face group — collection of photos containing the same person. */
    public record FaceGroup(
        String faceId,
        String name,
        Set<String> photoIds,
        Instant createdAt
    ) {}

    /** A curated memory lane — photos grouped by theme or event. */
    public record MemoryLane(
        String laneId,
        String title,
        String description,
        List<String> photoIds,
        Instant createdAt,
        String createdBy
    ) {}

    private final PhotoPersistence persistence; // nullable
    private final Map<String, Photo> photos = new ConcurrentHashMap<>();
    private final Map<String, FaceGroup> faceGroups = new ConcurrentHashMap<>();
    private final Map<String, MemoryLane> memoryLanes = new ConcurrentHashMap<>();
    private int nextPhotoId = 1;
    private int nextFaceId = 1;
    private int nextLaneId = 1;

    public PhotoService() { this(null); }

    public PhotoService(PhotoPersistence persistence) {
        this.persistence = persistence;
    }

    /** Import a photo (register metadata). */
    public Photo importPhoto(String filename, String ownerEntity,
                              Instant takenAt, String location) {
        var photoId = "photo-" + nextPhotoId++;
        var photo = new Photo(photoId, filename, ownerEntity, takenAt,
            Instant.now(), location, new HashSet<>(), new HashSet<>(),
            null, PhotoStatus.IMPORTED);
        photos.put(photoId, photo);
        if (persistence != null) persistence.savePhoto(photo);
        return photo;
    }

    /** Tag a photo with labels. */
    public Optional<Photo> tagPhoto(String photoId, Set<String> tags) {
        var photo = photos.get(photoId);
        if (photo == null) return Optional.empty();
        var allTags = new HashSet<>(photo.tags());
        allTags.addAll(tags);
        var updated = new Photo(photo.photoId(), photo.filename(), photo.ownerEntity(),
            photo.takenAt(), photo.importedAt(), photo.location(), allTags,
            photo.faces(), photo.perceptualHash(), PhotoStatus.TAGGED);
        photos.put(photoId, updated);
        if (persistence != null) persistence.savePhoto(updated);
        return Optional.of(updated);
    }

    /** Assign a face ID to a photo. */
    public Optional<Photo> assignFace(String photoId, String faceId) {
        var photo = photos.get(photoId);
        if (photo == null) return Optional.empty();
        var faces = new HashSet<>(photo.faces());
        faces.add(faceId);
        var updated = new Photo(photo.photoId(), photo.filename(), photo.ownerEntity(),
            photo.takenAt(), photo.importedAt(), photo.location(), photo.tags(),
            faces, photo.perceptualHash(), photo.status());
        photos.put(photoId, updated);
        if (persistence != null) persistence.savePhoto(updated);

        // Update face group
        faceGroups.computeIfAbsent(faceId, id -> {
            var newFaceId = "face-" + nextFaceId++;
            return new FaceGroup(id, "Unknown", new HashSet<>(), Instant.now());
        });
        var group = faceGroups.get(faceId);
        var photoIds = new HashSet<>(group.photoIds());
        photoIds.add(photoId);
        var updatedGroup = new FaceGroup(group.faceId(), group.name(),
            photoIds, group.createdAt());
        faceGroups.put(faceId, updatedGroup);
        if (persistence != null) persistence.saveFaceGroup(updatedGroup);

        return Optional.of(updated);
    }

    /** Name a face group. */
    public Optional<FaceGroup> nameFace(String faceId, String name) {
        var group = faceGroups.get(faceId);
        if (group == null) return Optional.empty();
        var updated = new FaceGroup(group.faceId(), name, group.photoIds(), group.createdAt());
        faceGroups.put(faceId, updated);
        if (persistence != null) persistence.saveFaceGroup(updated);
        return Optional.of(updated);
    }

    /** Create a memory lane. */
    public MemoryLane createMemoryLane(String title, String description,
                                        List<String> photoIds, String createdBy) {
        var laneId = "lane-" + nextLaneId++;
        var lane = new MemoryLane(laneId, title, description, List.copyOf(photoIds),
            Instant.now(), createdBy);
        memoryLanes.put(laneId, lane);
        if (persistence != null) persistence.saveMemoryLane(lane);
        return lane;
    }

    /** Search photos by tag. */
    public List<Photo> searchByTag(String tag) {
        return photos.values().stream()
            .filter(p -> p.tags().contains(tag.toLowerCase()))
            .sorted(Comparator.comparing(Photo::takenAt).reversed())
            .toList();
    }

    /** Search photos by location substring. */
    public List<Photo> searchByLocation(String location) {
        var lowerLoc = location.toLowerCase();
        return photos.values().stream()
            .filter(p -> p.location() != null && p.location().toLowerCase().contains(lowerLoc))
            .sorted(Comparator.comparing(Photo::takenAt).reversed())
            .toList();
    }

    /** Find potential duplicates (photos with same perceptual hash). */
    public Map<String, List<Photo>> findDuplicates() {
        return photos.values().stream()
            .filter(p -> p.perceptualHash() != null)
            .collect(Collectors.groupingBy(Photo::perceptualHash))
            .entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Get photo by ID. */
    public Optional<Photo> getPhoto(String photoId) {
        return Optional.ofNullable(photos.get(photoId));
    }

    /** Get face group by ID. */
    public Optional<FaceGroup> getFaceGroup(String faceId) {
        return Optional.ofNullable(faceGroups.get(faceId));
    }

    /** Get memory lane by ID. */
    public Optional<MemoryLane> getMemoryLane(String laneId) {
        return Optional.ofNullable(memoryLanes.get(laneId));
    }

    /** List all face groups. */
    public List<FaceGroup> listFaceGroups() {
        return new ArrayList<>(faceGroups.values());
    }

    /** List all memory lanes. */
    public List<MemoryLane> listMemoryLanes() {
        return new ArrayList<>(memoryLanes.values());
    }

    /** Total photos. */
    public int photoCount() { return photos.size(); }

    /** Human-readable summary. */
    public String describe() {
        if (photos.isEmpty()) return "The Gallery is empty — no photos imported yet.";
        var sb = new StringBuilder("=== Photo Fabric ===\n\n");
        sb.append("Photos: ").append(photos.size()).append("\n");
        sb.append("Face groups: ").append(faceGroups.size()).append("\n");
        sb.append("Memory lanes: ").append(memoryLanes.size()).append("\n\n");

        // Recent photos
        sb.append("Recent:\n");
        photos.values().stream()
            .sorted(Comparator.comparing(Photo::importedAt).reversed())
            .limit(5)
            .forEach(p -> sb.append("  [").append(p.photoId()).append("] ")
                .append(p.filename()).append(" (").append(p.tags()).append(")\n"));
        return sb.toString().stripTrailing();
    }
}

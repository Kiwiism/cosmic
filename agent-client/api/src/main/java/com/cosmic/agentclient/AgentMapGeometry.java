package com.cosmic.agentclient;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure map geometry used by the headless agent client. Keep this detached from
 * Cosmic server classes so the agent-client can run as a real external client.
 */
record AgentMapGeometry(int mapId, List<Foothold> footholds, List<LadderRope> ladderRopes, List<Portal> portals) {
    private static final int GROUND_SNAP_PIXELS = 20;
    private static final int GROUND_SCAN_PIXELS = 96;
    private static final int ROPE_GRAB_X_PIXELS = 8;

    Optional<Ground> groundAt(int x, int y) {
        return footholds.stream()
                .filter(foothold -> foothold.walkable())
                .filter(foothold -> foothold.spans(x))
                .map(foothold -> new Ground(foothold, foothold.yAt(x)))
                .filter(ground -> Math.abs(ground.y() - y) <= GROUND_SNAP_PIXELS)
                .min(Comparator.comparingInt(ground -> Math.abs(ground.y() - y)));
    }

    Optional<Ground> groundBelow(int x, int y) {
        return footholds.stream()
                .filter(foothold -> foothold.walkable())
                .filter(foothold -> foothold.spans(x))
                .map(foothold -> new Ground(foothold, foothold.yAt(x)))
                .filter(ground -> ground.y() >= y - 2)
                .filter(ground -> ground.y() - y <= GROUND_SCAN_PIXELS)
                .min(Comparator.comparingInt(ground -> ground.y() - y));
    }

    Optional<Ground> nearestGroundAtX(int x, int y) {
        return footholds.stream()
                .filter(foothold -> foothold.walkable())
                .filter(foothold -> foothold.spans(x))
                .map(foothold -> new Ground(foothold, foothold.yAt(x)))
                .min(Comparator.comparingInt(ground -> Math.abs(ground.y() - y)));
    }

    Optional<Ground> nearestSafeGround(int x, int y) {
        return footholds.stream()
                .filter(foothold -> foothold.walkable())
                .map(foothold -> {
                    int clampedX = Math.max(foothold.minX(), Math.min(foothold.maxX(), x));
                    return new Ground(foothold, foothold.yAt(clampedX));
                })
                .min(Comparator
                        .comparingInt((Ground ground) -> horizontalDistance(ground.foothold(), x))
                        .thenComparingInt(ground -> Math.abs(ground.y() - y)));
    }

    private static int horizontalDistance(Foothold foothold, int x) {
        if (x < foothold.minX()) {
            return foothold.minX() - x;
        }
        if (x > foothold.maxX()) {
            return x - foothold.maxX();
        }
        return 0;
    }

    Optional<Ground> landingBetween(int fromX, int fromY, int toX, int toY) {
        int minX = Math.min(fromX, toX) - 4;
        int maxX = Math.max(fromX, toX) + 4;
        int minY = Math.min(fromY, toY) - 2;
        int maxY = Math.max(fromY, toY) + GROUND_SNAP_PIXELS;
        return footholds.stream()
                .filter(Foothold::walkable)
                .filter(foothold -> foothold.maxX() >= minX && foothold.minX() <= maxX)
                .map(foothold -> new Ground(foothold, foothold.yAt(toX)))
                .filter(ground -> ground.foothold().spans(toX))
                .filter(ground -> ground.y() >= minY && ground.y() <= maxY)
                .min(Comparator.comparingInt(ground -> Math.abs(ground.y() - toY)));
    }

    Optional<LadderRope> grabbableLadderRope(int x, int y) {
        return ladderRopes.stream()
                .filter(ladderRope -> Math.abs(ladderRope.x() - x) <= ROPE_GRAB_X_PIXELS)
                .filter(ladderRope -> ladderRope.containsY(y))
                .min(Comparator.comparingInt(ladderRope -> Math.abs(ladderRope.x() - x)));
    }

    Optional<LadderRope> nearestLadderRopeBetween(int fromX, int fromY, int toY) {
        int minY = Math.min(fromY, toY);
        int maxY = Math.max(fromY, toY);
        return ladderRopes.stream()
                .filter(ladderRope -> ladderRope.overlapsY(minY, maxY))
                .min(Comparator
                        .comparingInt((LadderRope ladderRope) -> Math.abs(ladderRope.x() - fromX))
                        .thenComparingInt(ladderRope -> Math.abs(ladderRope.centerY() - ((fromY + toY) / 2))));
    }

    record Ground(Foothold foothold, int y) {
    }

    record Foothold(int id, int x1, int y1, int x2, int y2, int prevId, int nextId, boolean forbidFallDown) {
        boolean walkable() {
            return x1 != x2;
        }

        int minX() {
            return Math.min(x1, x2);
        }

        int maxX() {
            return Math.max(x1, x2);
        }

        boolean spans(int x) {
            return walkable() && x >= minX() - 2 && x <= maxX() + 2;
        }

        int yAt(int x) {
            if (!walkable()) {
                return Math.min(y1, y2);
            }
            double ratio = (x - x1) / (double) (x2 - x1);
            return (int) Math.round(y1 + ((y2 - y1) * ratio));
        }
    }

    record LadderRope(int index, boolean ladder, boolean upperFoothold, int x, int y1, int y2, int page,
                      String sourcePath) {
        int topY() {
            return Math.min(y1, y2);
        }

        int bottomY() {
            return Math.max(y1, y2);
        }

        int centerY() {
            return (topY() + bottomY()) / 2;
        }

        boolean containsY(int y) {
            return y >= topY() - 4 && y <= bottomY() + 4;
        }

        boolean overlapsY(int minY, int maxY) {
            return bottomY() >= minY && topY() <= maxY;
        }
    }

    record Portal(int index, String name, int type, int targetMapId, String targetPortalName, int x, int y,
                  String sourcePath) {
    }
}

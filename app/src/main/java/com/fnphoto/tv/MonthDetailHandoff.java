package com.fnphoto.tv;

/** Owns the wall's stop episode until the wall actually resumes after a detail launch. */
final class MonthDetailHandoff {
    private DetailSession current;
    private boolean detailOwnsWallStop;

    DetailSession openDetail() {
        current = new DetailSession();
        detailOwnsWallStop = true;
        return current;
    }

    void wallResumed() { detailOwnsWallStop = false; }

    boolean shouldPauseWallStop() { return !detailOwnsWallStop; }

    boolean hasPendingReturn() {
        return detailOwnsWallStop && current != null && current.returningToWall;
    }

    void reset() {
        current = null;
        detailOwnsWallStop = false;
    }

    final class DetailSession {
        private boolean returningToWall;

        boolean isCurrent() { return current == this; }

        void returnToWall() { returningToWall = true; }

        boolean shouldPauseStop(boolean wallResumed) {
            // A finishing detail is exempt only after the original wall has foreground
            // ownership. If another Activity covers the return, its stop must cancel.
            return isCurrent() && (!returningToWall || !wallResumed);
        }
    }

    @Override public String toString() {
        return detailOwnsWallStop ? hasPendingReturn() ? "RETURNING" : "DETAIL" : "WALL";
    }
}

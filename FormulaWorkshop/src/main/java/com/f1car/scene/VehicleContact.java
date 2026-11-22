package com.f1car.scene;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionEvent;
import com.jme3.bullet.collision.PhysicsCollisionListener;

/** Narrowphase geometry contact, excluding unrelated scenery and mere AABB overlap. */
public final class VehicleContact {
    private VehicleContact() {}

    public static boolean touching(PhysicsSpace space,FormulaCar first,FormulaCar second) {
        return new Query().touching(space,first,second);
    }

    /** Reusable callback for consecutive, synchronous pair tests. */
    public static final class Query implements PhysicsCollisionListener {
        private boolean contact;

        public boolean touching(PhysicsSpace space,FormulaCar first,FormulaCar second) {
            contact=false;
            space.pairTest(first.body,second.body,this);
            return contact;
        }

        @Override
        public void collision(PhysicsCollisionEvent event) {
            if(!contact && event.getDistance1()<=0) contact=true;
        }
    }
}

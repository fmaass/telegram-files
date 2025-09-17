import React, { useState } from "react";
import {
  DndContext,
  type DragEndEvent,
  KeyboardSensor,
  MouseSensor,
  TouchSensor,
  useDraggable,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import { Portal } from "@radix-ui/react-portal";
import { cn } from "@/lib/utils";

interface DraggableElementProps {
  children: React.ReactNode;
  className?: string;
}

const DraggableContent = ({
  children,
  className = "",
  style,
}: DraggableElementProps & { style: React.CSSProperties }) => {
  const { attributes, listeners, setNodeRef, transform, isDragging } =
    useDraggable({
      id: "draggable-element",
    });

  const transformStyle = transform
    ? {
        transform: `translate3d(${transform.x}px, ${transform.y}px, 0)`,
      }
    : {};

  const preventContextMenu = (e: React.MouseEvent | React.TouchEvent) => {
    e.preventDefault();
    e.stopPropagation();
  };

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      className={cn(
        `fixed select-none`,
        isDragging && "touch-none opacity-80 cursor-grabbing",
        !isDragging && "touch-auto",
        className,
      )}
      style={{
        ...style,
        ...transformStyle,
        touchAction: isDragging ? "none" : "auto",
        WebkitUserSelect: "none", // 禁用文本选择
        WebkitTouchCallout: isDragging ? "none" : "default", // 只在拖拽时禁用触摸回调
        userSelect: "none",
      }}
      onContextMenu={preventContextMenu}
    >
      <div
        className={`relative ${isDragging ? "scale-105 transition-transform" : ""}`}
      >
        {children}
      </div>
      {isDragging && (
        <div className="absolute inset-0 animate-pulse rounded-lg bg-black/5" />
      )}
    </div>
  );
};

const DraggableElement = (props: DraggableElementProps) => {
  const [position, setPosition] = useState({
    x: window.innerWidth - 60,
    y: window.innerHeight - 60,
  });

  const mouseSensor = useSensor(MouseSensor, {
    activationConstraint: {
      delay: 250,
      tolerance: 5,
    },
  });
  const touchSensor = useSensor(TouchSensor, {
    activationConstraint: {
      delay: 250,
      tolerance: 5,
    },
  });
  const keyboardSensor = useSensor(KeyboardSensor, {});
  const sensors = useSensors(mouseSensor, touchSensor, keyboardSensor);

  const handleDragEnd = (event: DragEndEvent) => {
    const { delta } = event;

    setPosition((prev) => {
      const newX = Math.max(
        0,
        Math.min(prev.x + delta.x, window.innerWidth - 40),
      );
      const newY = Math.max(
        0,
        Math.min(prev.y + delta.y, window.innerHeight - 40),
      );
      return { x: newX, y: newY };
    });
  };

  const style: React.CSSProperties = {
    top: position.y,
    left: position.x,
  };

  return (
    <Portal>
      <DndContext onDragEnd={handleDragEnd} sensors={sensors}>
        <div id="draggable-content">
          <DraggableContent {...props} style={style} />
        </div>
      </DndContext>
    </Portal>
  );
};

export default DraggableElement;

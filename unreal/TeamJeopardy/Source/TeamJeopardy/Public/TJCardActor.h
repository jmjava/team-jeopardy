#pragma once

#include "CoreMinimal.h"
#include "GameFramework/Actor.h"
#include "TJTypes.h"
#include "TJCardActor.generated.h"

class UMaterialInstanceDynamic;
class UStaticMeshComponent;
class UWidgetComponent;

/** One lit column header or dollar card. Click the mesh; the face is world-space text. */
UCLASS()
class TEAMJEOPARDY_API ATJCardActor : public AActor
{
	GENERATED_BODY()

public:
	ATJCardActor();

	void Setup(
		UMaterialInterface* BaseMaterial,
		const FString& InClueId,
		int32 InCategoryIndex,
		int32 InClueIndex,
		const FText& InLabel,
		ETJFaceKind InKind,
		const FLinearColor& InFaceColor,
		const FLinearColor& InFrameColor);

	void SetAnswered(bool bInAnswered);
	void SetHighlighted(bool bOn);

	const FString& GetClueId() const { return ClueId; }
	int32 GetCategoryIndex() const { return CategorySlot; }
	int32 GetClueIndex() const { return ClueSlot; }
	bool IsHeader() const { return ClueSlot == INDEX_NONE; }
	bool IsAnswered() const { return bAnswered; }

private:
	void ApplyFace();
	void ApplyMaterial();

	UPROPERTY()
	TObjectPtr<USceneComponent> Root;

	UPROPERTY()
	TObjectPtr<UStaticMeshComponent> Frame;

	UPROPERTY()
	TObjectPtr<UStaticMeshComponent> Body;

	UPROPERTY()
	TObjectPtr<UWidgetComponent> Face;

	UPROPERTY()
	TObjectPtr<UMaterialInstanceDynamic> FrameMaterial;

	UPROPERTY()
	TObjectPtr<UMaterialInstanceDynamic> FaceMaterial;

	FString ClueId;
	FText ValueLabel;
	FText Label;
	ETJFaceKind Kind = ETJFaceKind::Value;
	FLinearColor FaceColor = FLinearColor::White;
	FLinearColor FrameColor = FLinearColor::Black;
	int32 CategorySlot = INDEX_NONE;
	int32 ClueSlot = INDEX_NONE;
	bool bAnswered = false;
	bool bHighlighted = false;
};

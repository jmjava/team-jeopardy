#pragma once

#include "CoreMinimal.h"
#include "GameFramework/Actor.h"
#include "TJTypes.h"
#include "TJStudio.generated.h"

class ATJCardActor;
class UMaterialInstanceDynamic;
class USkyLightComponent;
class USpotLightComponent;
class UStaticMeshComponent;
class UWidgetComponent;

/** Cyclorama, lights, card grid, clue wall, and three podiums. */
UCLASS()
class TEAMJEOPARDY_API ATJStudio : public AActor
{
	GENERATED_BODY()

public:
	ATJStudio();

	virtual void BeginPlay() override;

	void BuildBoard(const FTJBoard& Board);
	void Sync(const FTJMatchView& View);

	bool HasBoard() const { return bBoardBuilt; }
	FVector GetBoardCenter() const { return BoardCenter; }

private:
	void AimLights();
	UStaticMeshComponent* MakeMesh(const TCHAR* Name, UStaticMesh* Mesh, const FVector& Scale, const FLinearColor& Color);
	UMaterialInstanceDynamic* Paint(UStaticMeshComponent* Mesh, const FLinearColor& Color);

	UPROPERTY()
	TObjectPtr<USceneComponent> Root;

	UPROPERTY()
	TObjectPtr<UStaticMeshComponent> Floor;

	UPROPERTY()
	TObjectPtr<UStaticMeshComponent> BackWall;

	UPROPERTY()
	TObjectPtr<UStaticMeshComponent> Ceiling;

	UPROPERTY()
	TObjectPtr<USpotLightComponent> KeyLight;

	UPROPERTY()
	TObjectPtr<USpotLightComponent> FillLight;

	UPROPERTY()
	TObjectPtr<USpotLightComponent> GoldLight;

	UPROPERTY()
	TObjectPtr<USkyLightComponent> Sky;

	UPROPERTY()
	TObjectPtr<UWidgetComponent> ClueWall;

	UPROPERTY()
	TArray<TObjectPtr<UStaticMeshComponent>> PodiumMeshes;

	UPROPERTY()
	TArray<TObjectPtr<UWidgetComponent>> PodiumFaces;

	UPROPERTY()
	TArray<TObjectPtr<UMaterialInstanceDynamic>> PodiumMaterials;

	UPROPERTY()
	TArray<TObjectPtr<ATJCardActor>> Cards;

	UPROPERTY()
	TObjectPtr<UMaterialInterface> ShapeMaterial;

	UPROPERTY()
	TObjectPtr<UStaticMesh> CubeMesh;

	UPROPERTY()
	TObjectPtr<UStaticMesh> PlaneMesh;

	UPROPERTY()
	TObjectPtr<UStaticMesh> CylinderMesh;

	FVector BoardCenter = FVector(0.f, 0.f, 450.f);
	bool bBoardBuilt = false;
};

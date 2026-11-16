package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CategoryAdminRequest;
import com.reclamos.backend.dto.request.RequestTypeAdminRequest;
import com.reclamos.backend.dto.request.SubcategoryAdminRequest;
import com.reclamos.backend.dto.response.CategoryAdminResponse;
import com.reclamos.backend.dto.response.RequestTypeAdminResponse;
import com.reclamos.backend.dto.response.SubcategoryAdminResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.exception.InvalidCatalogRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BE - DDA2-114/115/116 (US "Panel de administración del catálogo"): CRUD
 * administrativo de Categories/Subcategories/Request Types, separado de
 * {@link CatalogService} (sólo lectura, catálogo público filtrado por
 * active=true).
 *
 * Los métodos activate* / deactivate* son DDA2-120/121/122 (US "Activar/
 * desactivar entidades del catálogo") + DDA2-126/127/128/129 (US
 * "Integridad jerárquica y desactivación en cascada"):
 * <ul>
 *     <li>Desactivar una Category cascadea a todas sus Subcategories y, a
 *     través de ellas, a todos los Request Types (DDA2-126/127).</li>
 *     <li>Desactivar una Subcategory cascadea a sus Request Types
 *     (DDA2-126/127).</li>
 *     <li>Activar NUNCA cascadea hacia abajo — sólo valida hacia arriba
 *     (que el padre, y en el caso de Request Type también el abuelo,
 *     estén activos) y rechaza si no. Esto es deliberado: si activar
 *     resucitara en cascada a los hijos, se reactivarían entidades que el
 *     admin pudo haber desactivado independientemente antes.</li>
 * </ul>
 * Con esto, el invariante "no puede existir una entidad activa con un
 * padre inactivo" (DDA2-128/129) queda garantizado por construcción en
 * todos los caminos de escritura (crear, editar reasignando padre,
 * activar, desactivar) — no hace falta un chequeo periódico aparte.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CatalogAdminService {
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final RequestTypeRepository requestTypeRepository;
    private final TicketRepository ticketRepository;

    @Transactional(readOnly = true)
    public List<CategoryAdminResponse> listCategories() {
        return categoryRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    public CategoryAdminResponse createCategory(CategoryAdminRequest request) {
        if (categoryRepository.existsByNameIgnoreCase(request.getName())) {
            throw new InvalidCatalogRequestException(
                    "Ya existe una categoría con el nombre '" + request.getName() + "'");
        }

        Category category = new Category();
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        return toResponse(categoryRepository.save(category));
    }

    public CategoryAdminResponse updateCategory(Long categoryId, CategoryAdminRequest request) {
        Category category = lockCategory(categoryId);

        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(request.getName(), categoryId)) {
            throw new InvalidCatalogRequestException(
                    "Ya existe una categoría con el nombre '" + request.getName() + "'");
        }

        category.setName(request.getName());
        category.setDescription(request.getDescription());
        return toResponse(categoryRepository.save(category));
    }

    @Transactional(readOnly = true)
    public List<SubcategoryAdminResponse> listSubcategories(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La categoría solicitada no existe"));

        return subcategoryRepository.findByCategory_IdOrderByNameAsc(category.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public SubcategoryAdminResponse createSubcategory(SubcategoryAdminRequest request) {
        Category category = requireActiveCategoryForUpdate(request.getCategoryId());

        if (subcategoryRepository.existsByCategory_IdAndNameIgnoreCase(category.getId(), request.getName())) {
            throw new InvalidCatalogRequestException(
                    "Ya existe una subcategoría con el nombre '" + request.getName()
                            + "' en la categoría seleccionada");
        }

        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        subcategory.setName(request.getName());
        subcategory.setDescription(request.getDescription());
        return toResponse(subcategoryRepository.save(subcategory));
    }

    public SubcategoryAdminResponse updateSubcategory(Long subcategoryId, SubcategoryAdminRequest request) {
        Long originalCategoryId = subcategoryRepository.findCategoryIdById(subcategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La subcategoría solicitada no existe"));
        Map<Long, Category> lockedCategories = lockCategories(originalCategoryId, request.getCategoryId());
        Subcategory subcategory = subcategoryRepository.findByIdForUpdate(subcategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La subcategoría solicitada no existe"));
        if (!subcategory.getCategory().getId().equals(originalCategoryId)) {
            throw concurrentHierarchyChange();
        }

        // Sólo valida que la nueva Category esté activa si efectivamente se
        // está reasignando el padre ("no se permite asociar una entidad
        // NUEVA a un padre inactivo" aplica a creación y a reasignación; no
        // re-valida el padre actual si no cambia, para no bloquear una
        // edición de nombre/descripción sobre una Subcategory cuya Category
        // fue desactivada después de creada).
        Category category = lockedCategories.get(request.getCategoryId());
        if (!originalCategoryId.equals(request.getCategoryId()) && !category.isActive()) {
            throw new InvalidCatalogRequestException(
                    "La categoría seleccionada no existe o está inactiva");
        }

        if (subcategoryRepository.existsByCategory_IdAndNameIgnoreCaseAndIdNot(
                category.getId(), request.getName(), subcategoryId)) {
            throw new InvalidCatalogRequestException(
                    "Ya existe una subcategoría con el nombre '" + request.getName()
                            + "' en la categoría seleccionada");
        }

        subcategory.setCategory(category);
        subcategory.setName(request.getName());
        subcategory.setDescription(request.getDescription());
        return toResponse(subcategoryRepository.save(subcategory));
    }

    @Transactional(readOnly = true)
    public List<RequestTypeAdminResponse> listRequestTypes(Long subcategoryId) {
        Subcategory subcategory = subcategoryRepository.findById(subcategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La subcategoría solicitada no existe"));

        return requestTypeRepository.findBySubcategory_IdOrderByNameAsc(subcategory.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public RequestTypeAdminResponse createRequestType(RequestTypeAdminRequest request) {
        Subcategory subcategory = requireActiveSubcategoryForUpdate(request.getSubcategoryId());

        if (requestTypeRepository.existsByCodeIgnoreCase(request.getCode())) {
            throw new InvalidCatalogRequestException(
                    "Ya existe un Request Type con el código '" + request.getCode() + "'");
        }
        if (requestTypeRepository.existsBySubcategory_IdAndNameIgnoreCase(subcategory.getId(), request.getName())) {
            throw new InvalidCatalogRequestException(
                    "Ya existe un Request Type con el nombre '" + request.getName()
                            + "' en la subcategoría seleccionada");
        }

        RequestType requestType = new RequestType();
        requestType.setSubcategory(subcategory);
        applyFields(requestType, request);
        return toResponse(requestTypeRepository.save(requestType));
    }

    public RequestTypeAdminResponse updateRequestType(Long requestTypeId, RequestTypeAdminRequest request) {
        Long originalSubcategoryId = requestTypeRepository.findSubcategoryIdById(requestTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("El Request Type solicitado no existe"));
        Long originalCategoryId = subcategoryRepository.findCategoryIdById(originalSubcategoryId)
                .orElseThrow(this::concurrentHierarchyChange);
        Long targetCategoryId = subcategoryRepository.findCategoryIdById(request.getSubcategoryId())
                .orElseThrow(() -> new InvalidCatalogRequestException(
                        "La subcategoría seleccionada no existe o está inactiva"));
        Map<Long, Category> lockedCategories = lockCategories(
                originalCategoryId,
                targetCategoryId);
        Map<Long, Subcategory> lockedSubcategories = lockSubcategories(
                originalSubcategoryId, request.getSubcategoryId());
        RequestType requestType = requestTypeRepository.findByIdForUpdate(requestTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("El Request Type solicitado no existe"));
        if (!requestType.getSubcategory().getId().equals(originalSubcategoryId)) {
            throw concurrentHierarchyChange();
        }

        if (!lockedSubcategories.get(originalSubcategoryId).getCategory().getId().equals(originalCategoryId)
                || !lockedSubcategories.get(request.getSubcategoryId()).getCategory().getId()
                        .equals(targetCategoryId)) {
            throw concurrentHierarchyChange();
        }

        Subcategory subcategory = lockedSubcategories.get(request.getSubcategoryId());
        Category lockedTargetCategory = lockedCategories.get(subcategory.getCategory().getId());
        if (lockedTargetCategory == null) {
            throw concurrentHierarchyChange();
        }

        if (!originalSubcategoryId.equals(request.getSubcategoryId())
                && (!subcategory.isActive() || !lockedTargetCategory.isActive())) {
            throw new InvalidCatalogRequestException(
                    "La subcategoría seleccionada no existe o está inactiva");
        }

        if (requestTypeRepository.existsByCodeIgnoreCaseAndIdNot(request.getCode(), requestTypeId)) {
            throw new InvalidCatalogRequestException(
                    "Ya existe un Request Type con el código '" + request.getCode() + "'");
        }
        if (requestTypeRepository.existsBySubcategory_IdAndNameIgnoreCaseAndIdNot(
                subcategory.getId(), request.getName(), requestTypeId)) {
            throw new InvalidCatalogRequestException(
                    "Ya existe un Request Type con el nombre '" + request.getName()
                            + "' en la subcategoría seleccionada");
        }

        if (ticketRepository.existsByRequestType_Id(requestTypeId)
                && requestType.getBaseRisk() != request.getBaseRisk()) {
            throw new InvalidCatalogRequestException(
                    "No se puede modificar el riesgo base de un Request Type que ya tiene tickets asociados");
        }

        requestType.setSubcategory(subcategory);
        applyFields(requestType, request);
        return toResponse(requestTypeRepository.save(requestType));
    }

    public CategoryAdminResponse deactivateCategory(Long categoryId) {
        Category category = lockCategory(categoryId);
        category.setActive(false);
        CategoryAdminResponse response = toResponse(categoryRepository.save(category));

        // DDA2-126/127: cascadea a Subcategories y, a través de ellas, a
        // Request Types. Se fuerza active=false en todo el árbol sin
        // importar el estado previo de cada hijo (idempotente) — más
        // simple y más seguro que sólo tocar las que estaban activas.
        for (Subcategory subcategory : subcategoryRepository.findByCategoryIdOrderByIdAscForUpdate(categoryId)) {
            subcategory.setActive(false);
            subcategoryRepository.save(subcategory);
            deactivateRequestTypesOf(subcategory.getId());
        }

        return response;
    }

    public CategoryAdminResponse activateCategory(Long categoryId) {
        // Category es la raíz de la jerarquía: no tiene padre que validar.
        Category category = lockCategory(categoryId);
        category.setActive(true);
        return toResponse(categoryRepository.save(category));
    }

    public SubcategoryAdminResponse deactivateSubcategory(Long subcategoryId) {
        Subcategory subcategory = lockSubcategoryHierarchy(subcategoryId).subcategory();
        subcategory.setActive(false);
        SubcategoryAdminResponse response = toResponse(subcategoryRepository.save(subcategory));

        // DDA2-126/127: cascadea a sus Request Types.
        deactivateRequestTypesOf(subcategoryId);

        return response;
    }

    private void deactivateRequestTypesOf(Long subcategoryId) {
        for (RequestType requestType : requestTypeRepository.findBySubcategoryIdOrderByIdAscForUpdate(subcategoryId)) {
            requestType.setActive(false);
            requestTypeRepository.save(requestType);
        }
    }

    public SubcategoryAdminResponse activateSubcategory(Long subcategoryId) {
        LockedSubcategory hierarchy = lockSubcategoryHierarchy(subcategoryId);
        Subcategory subcategory = hierarchy.subcategory();
        Category category = hierarchy.category();
        if (!category.isActive()) {
            throw new InvalidCatalogRequestException(
                    "No se puede activar la subcategoría porque su categoría '"
                            + category.getName() + "' está inactiva");
        }

        subcategory.setActive(true);
        return toResponse(subcategoryRepository.save(subcategory));
    }

    public RequestTypeAdminResponse deactivateRequestType(Long requestTypeId) {
        RequestType requestType = lockRequestTypeHierarchy(requestTypeId).requestType();
        requestType.setActive(false);
        return toResponse(requestTypeRepository.save(requestType));
    }

    public RequestTypeAdminResponse activateRequestType(Long requestTypeId) {
        LockedRequestType hierarchy = lockRequestTypeHierarchy(requestTypeId);
        RequestType requestType = hierarchy.requestType();

        // Se valida la cadena completa (Subcategory Y Category), no sólo el
        // padre inmediato: "No puede existir un Request Type activo cuya
        // Subcategory o Category esté inactiva" es el mismo invariante que
        // DDA2-126/127/128/129 formaliza para toda la jerarquía.
        Subcategory subcategory = hierarchy.subcategory();
        if (!subcategory.isActive()) {
            throw new InvalidCatalogRequestException(
                    "No se puede activar el Request Type porque su subcategoría '"
                            + subcategory.getName() + "' está inactiva");
        }
        Category category = hierarchy.category();
        if (!category.isActive()) {
            throw new InvalidCatalogRequestException(
                    "No se puede activar el Request Type porque la categoría '"
                            + category.getName() + "' está inactiva");
        }

        requestType.setActive(true);
        return toResponse(requestTypeRepository.save(requestType));
    }

    private Category requireActiveCategoryForUpdate(Long categoryId) {
        return categoryRepository.findByIdForUpdate(categoryId)
                .filter(Category::isActive)
                .orElseThrow(() -> new InvalidCatalogRequestException(
                        "La categoría seleccionada no existe o está inactiva"));
    }

    private Subcategory requireActiveSubcategoryForUpdate(Long subcategoryId) {
        // DDA2-128/129: "no puede existir un Request Type activo cuya
        // Subcategory O Category esté inactiva" — se valida la cadena
        // completa, no sólo el padre inmediato, igual que en
        // activateRequestType. En teoría el invariante ya está garantizado
        // por construcción (deactivateCategory cascadea), pero validar acá
        // también es la defensa en profundidad correcta: barata y no
        // depende de que ningún otro camino de escritura se mantenga
        // correcto para siempre.
        Long categoryId = subcategoryRepository.findCategoryIdById(subcategoryId)
                .orElseThrow(() -> new InvalidCatalogRequestException(
                        "La subcategoría seleccionada no existe o está inactiva"));
        Category category = categoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new InvalidCatalogRequestException(
                        "La subcategoría seleccionada no existe o está inactiva"));
        Subcategory subcategory = subcategoryRepository.findByIdForUpdate(subcategoryId)
                .orElseThrow(() -> new InvalidCatalogRequestException(
                        "La subcategoría seleccionada no existe o está inactiva"));
        if (!subcategory.getCategory().getId().equals(category.getId())) {
            throw concurrentHierarchyChange();
        }
        if (!subcategory.isActive() || !category.isActive()) {
            throw new InvalidCatalogRequestException(
                    "La subcategoría seleccionada no existe o está inactiva");
        }
        return subcategory;
    }

    private Category lockCategory(Long categoryId) {
        return categoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La categoría solicitada no existe"));
    }

    private LockedSubcategory lockSubcategoryHierarchy(Long subcategoryId) {
        Long categoryId = subcategoryRepository.findCategoryIdById(subcategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La subcategoría solicitada no existe"));
        Category category = lockCategory(categoryId);
        Subcategory subcategory = subcategoryRepository.findByIdForUpdate(subcategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La subcategoría solicitada no existe"));
        if (!subcategory.getCategory().getId().equals(categoryId)) {
            throw concurrentHierarchyChange();
        }
        return new LockedSubcategory(category, subcategory);
    }

    private LockedRequestType lockRequestTypeHierarchy(Long requestTypeId) {
        Long subcategoryId = requestTypeRepository.findSubcategoryIdById(requestTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("El Request Type solicitado no existe"));
        Long categoryId = subcategoryRepository.findCategoryIdById(subcategoryId)
                .orElseThrow(this::concurrentHierarchyChange);
        Category category = lockCategory(categoryId);
        Subcategory subcategory = subcategoryRepository.findByIdForUpdate(subcategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("La subcategoría solicitada no existe"));
        RequestType requestType = requestTypeRepository.findByIdForUpdate(requestTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("El Request Type solicitado no existe"));
        if (!subcategory.getCategory().getId().equals(categoryId)
                || !requestType.getSubcategory().getId().equals(subcategoryId)) {
            throw concurrentHierarchyChange();
        }
        return new LockedRequestType(category, subcategory, requestType);
    }

    private Map<Long, Category> lockCategories(Long firstId, Long secondId) {
        Map<Long, Category> result = new LinkedHashMap<>();
        List.of(firstId, secondId).stream().distinct().sorted().forEach(id ->
                result.put(id, categoryRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new InvalidCatalogRequestException(
                                "La categoría seleccionada no existe o está inactiva"))));
        return result;
    }

    private Map<Long, Subcategory> lockSubcategories(Long firstId, Long secondId) {
        Map<Long, Subcategory> result = new LinkedHashMap<>();
        List.of(firstId, secondId).stream().distinct().sorted().forEach(id ->
                result.put(id, subcategoryRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new InvalidCatalogRequestException(
                                "La subcategoría seleccionada no existe o está inactiva"))));
        return result;
    }

    private InvalidCatalogRequestException concurrentHierarchyChange() {
        return new InvalidCatalogRequestException(
                "La jerarquía del catálogo cambió durante la operación; vuelva a intentarlo");
    }

    private record LockedSubcategory(Category category, Subcategory subcategory) {
    }

    private record LockedRequestType(Category category, Subcategory subcategory, RequestType requestType) {
    }

    private void applyFields(RequestType requestType, RequestTypeAdminRequest request) {
        requestType.setCode(request.getCode());
        requestType.setName(request.getName());
        requestType.setDescription(request.getDescription());
        requestType.setTicketType(request.getTicketType());
        requestType.setResponsibleAreaId(request.getResponsibleAreaId());
        requestType.setMinimumPriority(request.getMinimumPriority());
        requestType.setBaseRisk(request.getBaseRisk());
        requestType.setAffectedPopulationFactor(request.getAffectedPopulationFactor());
        requestType.setAllowsAnonymous(request.isAllowsAnonymous());
        requestType.setRequiresLocation(request.isRequiresLocation());
    }

    private CategoryAdminResponse toResponse(Category category) {
        CategoryAdminResponse response = new CategoryAdminResponse();
        response.setId(category.getId());
        response.setName(category.getName());
        response.setDescription(category.getDescription());
        response.setActive(category.isActive());
        response.setCreatedAt(category.getCreatedAt());
        response.setUpdatedAt(category.getUpdatedAt());
        return response;
    }

    private SubcategoryAdminResponse toResponse(Subcategory subcategory) {
        SubcategoryAdminResponse response = new SubcategoryAdminResponse();
        response.setId(subcategory.getId());
        response.setCategoryId(subcategory.getCategory().getId());
        response.setCategoryName(subcategory.getCategory().getName());
        response.setName(subcategory.getName());
        response.setDescription(subcategory.getDescription());
        response.setActive(subcategory.isActive());
        return response;
    }

    private RequestTypeAdminResponse toResponse(RequestType requestType) {
        RequestTypeAdminResponse response = new RequestTypeAdminResponse();
        response.setId(requestType.getId());
        response.setSubcategoryId(requestType.getSubcategory().getId());
        response.setSubcategoryName(requestType.getSubcategory().getName());
        response.setCode(requestType.getCode());
        response.setName(requestType.getName());
        response.setDescription(requestType.getDescription());
        response.setTicketType(requestType.getTicketType());
        response.setResponsibleAreaId(requestType.getResponsibleAreaId());
        response.setMinimumPriority(requestType.getMinimumPriority());
        response.setBaseRisk(requestType.getBaseRisk());
        response.setAffectedPopulationFactor(requestType.getAffectedPopulationFactor());
        response.setAllowsAnonymous(requestType.isAllowsAnonymous());
        response.setRequiresLocation(requestType.isRequiresLocation());
        response.setActive(requestType.isActive());
        return response;
    }
}
